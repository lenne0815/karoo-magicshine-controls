package com.lenne0815.karoomagicshine.extension

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lenne0815.karoomagicshine.MagicshineModule
import com.lenne0815.karoomagicshine.MagicshineProtocol
import com.lenne0815.karoomagicshine.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MagicshineControlService : Service() {

    interface Listener {
        fun onStatus(status: String) {}
        fun onConnectionStatus(status: String) {}
        fun onBatteryStatus(status: String) {}
        fun onTemperatureStatus(status: String) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): MagicshineControlService = this@MagicshineControlService
    }

    companion object {
        private const val TAG = "MagicshineSvc"
        private const val CHANNEL_ID = "magicshine_background"
        private const val NOTIFICATION_ID = 4042
        const val ACTION_TOGGLE_100 = "com.lenne0815.karoomagicshine.action.TOGGLE_100"
        const val ACTION_FIELD_VISIBLE = "com.lenne0815.karoomagicshine.action.FIELD_VISIBLE"
        const val ACTION_FIELD_HIDDEN = "com.lenne0815.karoomagicshine.action.FIELD_HIDDEN"
    }

    private val binder = LocalBinder()
    private val listeners = linkedSetOf<Listener>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var pendingConnectJob: Job? = null
    @Volatile private var pendingToggleJob: Job? = null
    @Volatile private var pendingExtensionRetryJob: Job? = null
    @Volatile private var activeFieldViews: Int = 0
    @Volatile private var extensionReady: Boolean = false
    @Volatile private var pendingAutoConnect: Boolean = false
    @Volatile private var foregroundHeld: Boolean = false

    private val controller by lazy {
        MagicshineBleController(
            applicationContext,
            onStatus = { status -> listeners.toList().forEach { it.onStatus(status) } },
            onConnectionStatus = { status -> listeners.toList().forEach { it.onConnectionStatus(status) } },
            onBatteryStatus = { status -> listeners.toList().forEach { it.onBatteryStatus(status) } },
            onTemperatureStatus = { status -> listeners.toList().forEach { it.onTemperatureStatus(status) } },
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_100 -> handleToggle100()
            ACTION_FIELD_VISIBLE -> markFieldVisible()
            ACTION_FIELD_HIDDEN -> markFieldHidden()
        }
        return START_NOT_STICKY
    }

    private fun markFieldVisible() {
        val wasInvisible = activeFieldViews == 0
        activeFieldViews += 1
        Log.d(TAG, "field visible count=$activeFieldViews")
        if (wasInvisible && !AppUiState.isActive(this) && extensionReady) {
            ensureConnectedInternal(forceRestart = false, delayMs = 250)
        } else if (wasInvisible && !AppUiState.isActive(this)) {
            pendingAutoConnect = true
            Log.d(TAG, "field visible before extension ready; deferring connect")
        }
    }

    private fun markFieldHidden() {
        activeFieldViews = (activeFieldViews - 1).coerceAtLeast(0)
        Log.d(TAG, "field hidden count=$activeFieldViews")
        if (activeFieldViews == 0) {
            stopRepeatingCommand()
            disconnect()
            stopForegroundIfHeld()
        }
    }

    private fun markExtensionReady() {
        extensionReady = true
        Log.d(TAG, "extension ready")
        if (pendingAutoConnect && activeFieldViews > 0 && !AppUiState.isActive(this)) {
            pendingAutoConnect = false
            ensureConnectedInternal(forceRestart = false, delayMs = 250)
        }
    }

    fun onExtensionReadyFromBoundClient() {
        markExtensionReady()
    }

    private fun cancelImmediateWork() {
        pendingConnectJob?.cancel()
        pendingConnectJob = null
        pendingToggleJob?.cancel()
        pendingToggleJob = null
    }

    private fun cancelPendingWork() {
        cancelImmediateWork()
        pendingExtensionRetryJob?.cancel()
        pendingExtensionRetryJob = null
    }

    private fun ensureConnectedInternal(forceRestart: Boolean, delayMs: Long) {
        cancelImmediateWork()
        if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return
        if (controller.currentPreferredAddress() == null) {
            Log.d(TAG, "ensureConnected aborted: no preferred lamp")
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }
        Log.d(TAG, "ensureConnected queued forceRestart=$forceRestart delayMs=$delayMs")
        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        controller.startDiscovery(forceRestart = forceRestart)
        pendingConnectJob = scope.launch {
            Log.d(TAG, "pending connect fired forceRestart=$forceRestart")
            delay(delayMs)
            controller.connect()
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingConnectJob === job) pendingConnectJob = null
            }
        }
    }

    private fun handleToggle100() {
        cancelPendingWork()
        val enabled = LightActionReceiver.isToggleEnabled(this)
        val snapshot = SharedLightState.get(this)
        val targetModule = when (snapshot.lastOnTarget) {
            SharedLightState.OutputTarget.HIGH -> MagicshineModule.MODULE_2
            SharedLightState.OutputTarget.LOW,
            SharedLightState.OutputTarget.OFF -> MagicshineModule.MODULE_1
        }
        val targetPercent = snapshot.lastOnLevelPercent ?: 100
        if (enabled && controller.hasLiveConnection()) {
            LightActionReceiver.setToggleEnabled(this, false)
            SharedLightState.set(this, SharedLightState.OutputTarget.OFF, null)
            LightFieldState.set(this, LightFieldState.STATUS_CONNECTED)
            controller.send(MagicshineProtocol.buildPresetFrame(targetModule, 0))
            return
        }

        if (controller.currentPreferredAddress() == null) {
            LightActionReceiver.setToggleEnabled(this, false)
            SharedLightState.set(this, SharedLightState.OutputTarget.OFF, null)
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }

        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        pendingToggleJob = scope.launch {
            if (!controller.hasLiveConnection() && !controller.hasConnectInFlight()) {
                controller.connect()
            }
            repeat(60) {
                if (controller.hasLiveConnection()) return@repeat
                delay(50)
            }
            if (!controller.hasLiveConnection()) {
                LightActionReceiver.setToggleEnabled(this@MagicshineControlService, false)
                SharedLightState.set(this@MagicshineControlService, SharedLightState.OutputTarget.OFF, null)
                return@launch
            }
            LightActionReceiver.setToggleEnabled(this@MagicshineControlService, true)
            SharedLightState.set(
                this@MagicshineControlService,
                when (targetModule) {
                    MagicshineModule.MODULE_2 -> SharedLightState.OutputTarget.HIGH
                    MagicshineModule.MODULE_1 -> SharedLightState.OutputTarget.LOW
                },
                targetPercent,
            )
            LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_CONNECTED)
            controller.send(MagicshineProtocol.buildPresetFrame(targetModule, targetPercent))
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingToggleJob === job) pendingToggleJob = null
            }
        }
    }

    fun registerListener(listener: Listener) {
        listeners.add(listener)
        listener.onStatus(controller.currentStatus())
        listener.onConnectionStatus(controller.currentConnectionStatus())
        listener.onBatteryStatus(controller.currentBatteryStatus())
        listener.onTemperatureStatus(controller.currentTemperatureStatus())
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun startDiscovery(forceRestart: Boolean = false) = controller.startDiscovery(forceRestart)
    fun stopDiscovery() = controller.stopDiscovery()
    fun setPreferredAddress(address: String?) = controller.setPreferredAddress(address)
    fun currentPreferredAddress(): String? = controller.currentPreferredAddress()
    fun currentLampCandidates(): List<LampCandidate> = controller.currentLampCandidates()
    fun currentSelectedLamp(): LampCandidate? = controller.currentSelectedLamp()
    fun connect() {
        cancelPendingWork()
        controller.connect()
    }
    fun ensureConnectedFromExtension() {
        startForegroundForExtension("Searching for lamp")
        cancelImmediateWork()
        if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return
        if (controller.currentPreferredAddress() == null) {
            Log.d(TAG, "extension ensureConnected aborted: no preferred lamp")
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }
        Log.d(TAG, "extension ensureConnected: start discovery first")
        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        controller.startDiscovery(forceRestart = true)
        pendingConnectJob = scope.launch {
            repeat(24) {
                if (AppUiState.isActive(this@MagicshineControlService)) return@launch
                if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return@launch
                val selected = controller.currentSelectedLamp()
                if (selected?.address == controller.currentPreferredAddress()) {
                    Log.d(TAG, "extension ensureConnected: preferred lamp found, connecting")
                    controller.connect()
                    return@launch
                }
                delay(500)
            }
            Log.d(TAG, "extension ensureConnected: timeout waiting for lamp, connecting anyway")
            controller.connect()
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingConnectJob === job) pendingConnectJob = null
            }
        }
        pendingExtensionRetryJob?.cancel()
        pendingExtensionRetryJob = scope.launch {
            repeat(3) { attempt ->
                delay(3500)
                if (AppUiState.isActive(this@MagicshineControlService)) return@launch
                if (controller.currentPreferredAddress() == null) return@launch
                if (controller.hasLiveConnection()) return@launch
                if (controller.hasConnectInFlight()) return@repeat
                Log.d(TAG, "extension retry ${attempt + 1}")
                ensureConnectedInternal(forceRestart = true, delayMs = 900)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingExtensionRetryJob === job) pendingExtensionRetryJob = null
            }
        }
    }
    fun disconnect() {
        cancelPendingWork()
        controller.disconnect()
        stopForegroundIfHeld()
    }
    fun send(frameHex: String) = controller.send(frameHex)
    fun startRepeatingCommand(frameHex: String, intervalMs: Long = 1500L) =
        controller.startRepeatingCommand(frameHex, intervalMs)
    fun stopRepeatingCommand() = controller.stopRepeatingCommand()
    fun currentStatus(): String = controller.currentStatus()
    fun currentConnectionStatus(): String = controller.currentConnectionStatus()
    fun currentBatteryStatus(): String = controller.currentBatteryStatus()
    fun currentTemperatureStatus(): String = controller.currentTemperatureStatus()
    fun hasLiveConnection(): Boolean = controller.hasLiveConnection()
    fun hasConnectInFlight(): Boolean = controller.hasConnectInFlight()
    fun clearStalePublishedConnectionState() = controller.clearStalePublishedConnectionState()

    override fun onDestroy() {
        stopForegroundIfHeld()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Magicshine background",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startForegroundForExtension(content: String) {
        if (foregroundHeld) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Magicshine")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundHeld = true
    }

    private fun stopForegroundIfHeld() {
        if (!foregroundHeld) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundHeld = false
    }
}
