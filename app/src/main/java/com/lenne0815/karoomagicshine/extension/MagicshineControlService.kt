package com.lenne0815.karoomagicshine.extension

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val EXTENSION_DISCOVERY_WAIT_MS = 8_000L
        private const val EXTENSION_DISCOVERY_POLL_MS = 500L
        private const val EXTENSION_RETRY_COOLDOWN_MS = 15_000L
        private const val BLUETOOTH_RETRY_ATTEMPTS = 8
        private const val BLUETOOTH_RETRY_WAIT_MS = 2_000L
        private const val UI_RETRY_ATTEMPTS = 3
        private const val UI_RETRY_CONNECT_WAIT_MS = 4_000L
        private const val UI_RETRY_POLL_MS = 100L
        private const val RIDE_FLASH_DURATION_MS = 5_000L
        const val ACTION_TOGGLE_100 = "com.lenne0815.karoomagicshine.action.TOGGLE_100"
        const val ACTION_FLASH_5_SECONDS = "com.lenne0815.karoomagicshine.action.FLASH_5_SECONDS"
        const val ACTION_RETRY_CONNECT = "com.lenne0815.karoomagicshine.action.RETRY_CONNECT"
        const val ACTION_FIELD_VISIBLE = "com.lenne0815.karoomagicshine.action.FIELD_VISIBLE"
        const val ACTION_FIELD_HIDDEN = "com.lenne0815.karoomagicshine.action.FIELD_HIDDEN"
        const val ACTION_REQUEST_KAROO_BLUETOOTH =
            "com.lenne0815.karoomagicshine.action.REQUEST_KAROO_BLUETOOTH"
    }

    private val binder = LocalBinder()
    private val listeners = linkedSetOf<Listener>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var pendingConnectJob: Job? = null
    @Volatile private var pendingToggleJob: Job? = null
    @Volatile private var pendingFlashJob: Job? = null
    @Volatile private var pendingExtensionRetryJob: Job? = null
    @Volatile private var pendingBluetoothRetryJob: Job? = null
    @Volatile private var pendingConnectAfterBluetooth: Boolean = false
    @Volatile private var activeFieldViews: Int = 0
    @Volatile private var extensionReady: Boolean = false
    @Volatile private var pendingAutoConnect: Boolean = false
    @Volatile private var foregroundHeld: Boolean = false

    private val controller by lazy {
        MagicshineBleController(
            applicationContext,
            onStatus = { status -> listeners.toList().forEach { it.onStatus(status) } },
            onConnectionStatus = { status -> listeners.toList().forEach { it.onConnectionStatus(status) } },
            onBatteryStatus = { status ->
                RideFieldState.setBatteryStatus(applicationContext, status)
                listeners.toList().forEach { it.onBatteryStatus(status) }
            },
            onTemperatureStatus = { status -> listeners.toList().forEach { it.onTemperatureStatus(status) } },
        )
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            Log.d(TAG, "bluetooth state changed state=$state pending=$pendingConnectAfterBluetooth")
            if (state == BluetoothAdapter.STATE_ON && pendingConnectAfterBluetooth) {
                pendingConnectAfterBluetooth = false
                pendingBluetoothRetryJob?.cancel()
                pendingBluetoothRetryJob = null
                ensureConnectedFromExtension()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        RideFieldState.stopFlash(this)
        RideFieldState.setBatteryStatus(this, "?")
        ensureNotificationChannel()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_100 -> handleToggle100()
            ACTION_FLASH_5_SECONDS -> handleRideFlash()
            ACTION_RETRY_CONNECT -> retryDiscoveryAndConnect()
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
        pendingBluetoothRetryJob?.cancel()
        pendingBluetoothRetryJob = null
        pendingConnectAfterBluetooth = false
    }

    private fun ensureConnectedInternal(forceRestart: Boolean, delayMs: Long) {
        cancelImmediateWork()
        if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return
        if (!controller.isBluetoothEnabled()) {
            waitForBluetoothAndRetry("ensureConnected")
            return
        }
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

    private fun retryDiscoveryAndConnect() {
        cancelPendingWork()
        if (!controller.isBluetoothEnabled()) {
            waitForBluetoothAndRetry("manual retry")
            return
        }
        if (controller.currentPreferredAddress() == null) {
            Log.d(TAG, "retry connect aborted: no preferred lamp")
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }
        Log.d(TAG, "retry connect: force restart discovery")
        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        pendingConnectJob = scope.launch {
            repeat(UI_RETRY_ATTEMPTS) { attempt ->
                if (controller.hasLiveConnection()) return@launch
                Log.d(TAG, "retry connect attempt ${attempt + 1}/$UI_RETRY_ATTEMPTS")
                LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_SEARCHING)
                controller.startDiscovery(forceRestart = true)
                delay(if (attempt == 0) 300 else 900)
                if (!controller.hasLiveConnection() && !controller.hasConnectInFlight()) {
                    controller.connect()
                }
                val connected = waitForConnectionResult(UI_RETRY_CONNECT_WAIT_MS)
                if (connected) return@launch
            }
            if (!controller.hasLiveConnection()) {
                LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_NO_DEVICE)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingConnectJob === job) pendingConnectJob = null
            }
        }
    }

    private suspend fun waitForConnectionResult(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / UI_RETRY_POLL_MS).toInt().coerceAtLeast(1)
        repeat(steps) {
            if (controller.hasLiveConnection()) return true
            if (!controller.hasConnectInFlight() && it > 0) return false
            delay(UI_RETRY_POLL_MS)
        }
        return controller.hasLiveConnection()
    }

    private fun handleToggle100() {
        cancelRideFlash()
        cancelPendingWork()
        val enabled = LightActionReceiver.isToggleEnabled(this)
        val snapshot = SharedLightState.get(this)
        val targetModule = when (snapshot.lastOnTarget) {
            SharedLightState.OutputTarget.HIGH -> MagicshineModule.MODULE_2
            SharedLightState.OutputTarget.LOW,
            SharedLightState.OutputTarget.OFF -> MagicshineModule.MODULE_1
        }
        val targetPercent = snapshot.lastOnLevelPercent ?: 100
        val targetMode = snapshot.lastOnMode
        if (enabled && controller.hasLiveConnection()) {
            controller.stopRepeatingCommand()
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
        if (!controller.isBluetoothEnabled()) {
            LightActionReceiver.setToggleEnabled(this, false)
            LightFieldState.set(this, LightFieldState.STATUS_DISCONNECTED)
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
                targetMode,
            )
            LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_CONNECTED)
            when (targetMode) {
                SharedLightState.Mode.STEADY ->
                    controller.send(MagicshineProtocol.buildPresetFrame(targetModule, targetPercent))
                SharedLightState.Mode.SOS ->
                    controller.startRepeatingCommand(
                        MagicshineProtocol.buildModeFrame(
                            targetModule,
                            com.lenne0815.karoomagicshine.MagicshineMode.SOS,
                        ),
                        1_500L,
                    )
                SharedLightState.Mode.BLITZ ->
                    controller.startRepeatingCommand(
                        MagicshineProtocol.buildModeFrame(
                            targetModule,
                            com.lenne0815.karoomagicshine.MagicshineMode.BLITZ,
                        ),
                        1_500L,
                    )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingToggleJob === job) pendingToggleJob = null
            }
        }
    }

    private fun handleRideFlash() {
        cancelRideFlash()
        cancelPendingWork()
        if (controller.currentPreferredAddress() == null) {
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }
        if (!controller.isBluetoothEnabled()) {
            LightFieldState.set(this, LightFieldState.STATUS_DISCONNECTED)
            return
        }

        val previousState = SharedLightState.get(this)
        pendingFlashJob = scope.launch {
            if (!controller.hasLiveConnection() && !controller.hasConnectInFlight()) {
                controller.connect()
            }
            if (!waitForConnectionResult(UI_RETRY_CONNECT_WAIT_MS)) {
                LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_NO_DEVICE)
                return@launch
            }

            val flashModule = moduleFor(previousState.outputTarget, previousState.lastOnTarget)
            val flashFrame = MagicshineProtocol.buildModeFrame(flashModule, com.lenne0815.karoomagicshine.MagicshineMode.BLITZ)
            RideFieldState.startFlash(this@MagicshineControlService, RIDE_FLASH_DURATION_MS)
            controller.stopRepeatingCommand()
            controller.startRepeatingCommand(flashFrame, 1_500L)
            delay(RIDE_FLASH_DURATION_MS)
            controller.stopRepeatingCommand()
            restoreLightState(previousState)
            RideFieldState.stopFlash(this@MagicshineControlService)
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingFlashJob === job) {
                    pendingFlashJob = null
                    RideFieldState.stopFlash(this@MagicshineControlService)
                }
            }
        }
    }

    private fun restoreLightState(snapshot: SharedLightState.Snapshot) {
        if (snapshot.outputTarget == SharedLightState.OutputTarget.OFF) {
            controller.send(MagicshineProtocol.buildPresetFrame(MagicshineModule.MODULE_1, 0))
            controller.send(MagicshineProtocol.buildPresetFrame(MagicshineModule.MODULE_2, 0))
            return
        }

        val module = moduleFor(snapshot.outputTarget, snapshot.lastOnTarget)
        val mode = when (snapshot.mode) {
            SharedLightState.Mode.STEADY -> null
            SharedLightState.Mode.SOS -> com.lenne0815.karoomagicshine.MagicshineMode.SOS
            SharedLightState.Mode.BLITZ -> com.lenne0815.karoomagicshine.MagicshineMode.BLITZ
        }
        if (mode == null) {
            controller.send(MagicshineProtocol.buildPresetFrame(module, snapshot.levelPercent ?: 100))
        } else {
            controller.startRepeatingCommand(MagicshineProtocol.buildModeFrame(module, mode), 1_500L)
        }
    }

    private fun moduleFor(
        outputTarget: SharedLightState.OutputTarget,
        fallbackTarget: SharedLightState.OutputTarget,
    ): MagicshineModule = when (if (outputTarget == SharedLightState.OutputTarget.OFF) fallbackTarget else outputTarget) {
        SharedLightState.OutputTarget.HIGH -> MagicshineModule.MODULE_2
        SharedLightState.OutputTarget.LOW,
        SharedLightState.OutputTarget.OFF -> MagicshineModule.MODULE_1
    }

    private fun cancelRideFlash() {
        val job = pendingFlashJob ?: return
        pendingFlashJob = null
        job.cancel()
        controller.stopRepeatingCommand()
        RideFieldState.stopFlash(this)
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
    fun retryDiscoveryAndConnectFromUi() {
        retryDiscoveryAndConnect()
    }
    fun ensureConnectedFromExtension() {
        startForegroundForExtension("Searching for lamp")
        cancelImmediateWork()
        if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return
        if (!controller.isBluetoothEnabled()) {
            waitForBluetoothAndRetry("extension ensureConnected")
            return
        }
        if (controller.currentPreferredAddress() == null) {
            Log.d(TAG, "extension ensureConnected aborted: no preferred lamp")
            LightFieldState.set(this, LightFieldState.STATUS_NO_DEVICE)
            return
        }
        Log.d(TAG, "extension ensureConnected: start discovery first")
        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        controller.startDiscovery(forceRestart = false)
        pendingConnectJob = scope.launch {
            repeat((EXTENSION_DISCOVERY_WAIT_MS / EXTENSION_DISCOVERY_POLL_MS).toInt()) {
                if (AppUiState.isActive(this@MagicshineControlService)) return@launch
                if (controller.hasLiveConnection() || controller.hasConnectInFlight()) return@launch
                val selected = controller.currentSelectedLamp()
                if (selected?.address == controller.currentPreferredAddress()) {
                    Log.d(TAG, "extension ensureConnected: preferred lamp found, connecting")
                    controller.connect()
                    return@launch
                }
                delay(EXTENSION_DISCOVERY_POLL_MS)
            }
            Log.d(TAG, "extension ensureConnected: timeout waiting for lamp, stopping discovery")
            controller.stopDiscovery()
            LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_NO_DEVICE)
            stopForegroundIfHeld()
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingConnectJob === job) pendingConnectJob = null
            }
        }
        pendingExtensionRetryJob?.cancel()
        pendingExtensionRetryJob = scope.launch {
            repeat(2) { attempt ->
                delay(EXTENSION_RETRY_COOLDOWN_MS)
                if (AppUiState.isActive(this@MagicshineControlService)) return@launch
                if (controller.currentPreferredAddress() == null) return@launch
                if (!controller.isBluetoothEnabled()) {
                    Log.d(TAG, "extension retry suppressed: bluetooth unavailable")
                    LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_DISCONNECTED)
                    stopForegroundIfHeld()
                    return@launch
                }
                if (controller.hasLiveConnection()) return@launch
                if (controller.hasConnectInFlight()) return@repeat
                Log.d(TAG, "extension retry ${attempt + 1}")
                ensureConnectedInternal(forceRestart = false, delayMs = 1400)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingExtensionRetryJob === job) pendingExtensionRetryJob = null
            }
        }
    }
    fun disconnect() {
        cancelRideFlash()
        cancelPendingWork()
        controller.disconnect()
        stopForegroundIfHeld()
    }
    fun send(frameHex: String) {
        cancelRideFlash()
        controller.send(frameHex)
    }
    fun startRepeatingCommand(frameHex: String, intervalMs: Long = 1500L) {
        cancelRideFlash()
        controller.startRepeatingCommand(frameHex, intervalMs)
    }
    fun stopRepeatingCommand() {
        cancelRideFlash()
        controller.stopRepeatingCommand()
    }
    fun currentStatus(): String = controller.currentStatus()
    fun currentConnectionStatus(): String = controller.currentConnectionStatus()
    fun currentBatteryStatus(): String = controller.currentBatteryStatus()
    fun currentTemperatureStatus(): String = controller.currentTemperatureStatus()
    fun hasLiveConnection(): Boolean = controller.hasLiveConnection()
    fun hasConnectInFlight(): Boolean = controller.hasConnectInFlight()
    fun clearStalePublishedConnectionState() = controller.clearStalePublishedConnectionState()

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        cancelPendingWork()
        stopForegroundIfHeld()
        super.onDestroy()
    }

    private fun waitForBluetoothAndRetry(reason: String) {
        Log.d(TAG, "$reason waiting for Karoo Bluetooth")
        requestKarooBluetooth(reason)
        pendingConnectAfterBluetooth = true
        LightFieldState.set(this, LightFieldState.STATUS_SEARCHING)
        if (pendingBluetoothRetryJob?.isActive == true) return
        pendingBluetoothRetryJob = scope.launch {
            repeat(BLUETOOTH_RETRY_ATTEMPTS) { attempt ->
                delay(BLUETOOTH_RETRY_WAIT_MS)
                if (!pendingConnectAfterBluetooth) return@launch
                Log.d(TAG, "bluetooth retry ${attempt + 1}/$BLUETOOTH_RETRY_ATTEMPTS for $reason")
                requestKarooBluetooth("$reason retry ${attempt + 1}")
                if (controller.isBluetoothEnabled()) {
                    pendingConnectAfterBluetooth = false
                    ensureConnectedFromExtension()
                    return@launch
                }
            }
            if (pendingConnectAfterBluetooth) {
                Log.d(TAG, "$reason gave up waiting for Bluetooth")
                pendingConnectAfterBluetooth = false
                LightFieldState.set(this@MagicshineControlService, LightFieldState.STATUS_DISCONNECTED)
                stopForegroundIfHeld()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingBluetoothRetryJob === job) pendingBluetoothRetryJob = null
            }
        }
    }

    private fun requestKarooBluetooth(reason: String) {
        Log.d(TAG, "requesting Karoo Bluetooth access ($reason)")
        sendBroadcast(
            Intent(ACTION_REQUEST_KAROO_BLUETOOTH)
                .setPackage(packageName),
        )
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
