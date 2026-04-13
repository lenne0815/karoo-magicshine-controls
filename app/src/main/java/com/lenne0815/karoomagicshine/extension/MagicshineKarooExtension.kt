package com.lenne0815.karoomagicshine.extension

import android.app.Service.RECEIVER_EXPORTED
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth

class MagicshineKarooExtension : KarooExtension("karoo-magicshine-controls", "1.0") {
    companion object {
        private const val TAG = "MagicshineExt"
        private const val ACTION_RIDE_APP_OPENED = "io.hammerhead.intent.action.RIDE_APP_OPENED"
        private const val ACTION_RIDE_STOP = "io.hammerhead.hx.intent.action.RIDE_STOP"
    }

    private val karooSystem by lazy { KarooSystemService(this) }
    private var lightService: MagicshineControlService? = null
    private var lightLifecycleHandler: LightLifecycleHandler? = null
    private var karooConnected = false
    @Volatile private var pendingRideOpen = false
    @Volatile private var lastControllerStatus: String = "idle"
    @Volatile private var lastConnectionStatus: String = "disconnected"

    private val serviceListener = object : MagicshineControlService.Listener {
        override fun onStatus(status: String) = handleControllerStatus(status)
        override fun onConnectionStatus(status: String) = handleConnectionStatus(status)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? MagicshineControlService.LocalBinder)?.getService() ?: return
            lightService = service
            service.registerListener(serviceListener)
            if (karooConnected) {
                service.onExtensionReadyFromBoundClient()
                LightFieldState.set(this@MagicshineKarooExtension, LightFieldState.STATUS_IDLE)
                lightLifecycleHandler?.shutdownHandling()
                lightLifecycleHandler = LightLifecycleHandler(
                    this@MagicshineKarooExtension,
                    karooSystem,
                    service,
                ).also {
                    it.startHandling()
                    if (pendingRideOpen) {
                        it.ensureConnectedNow("pending-ride-open")
                    } else {
                        it.ensureConnectedNow("extension-ready")
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lightLifecycleHandler?.shutdownHandling()
            lightLifecycleHandler = null
            lightService = null
            LightFieldState.set(this@MagicshineKarooExtension, LightFieldState.STATUS_DISCONNECTED)
        }
    }

    private val rideAppOpenedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received ride app opened")
            pendingRideOpen = true
            lightLifecycleHandler?.ensureConnectedNow("ride-app-opened")
        }
    }

    private val rideStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received ride stop")
            pendingRideOpen = false
            lightLifecycleHandler?.disconnectNow("ride-stop")
        }
    }

    override val types by lazy {
        listOf(
            LightControlsDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Extension onCreate")
        AppUiState.setActive(this, false)
        registerReceiver(
            rideAppOpenedReceiver,
            IntentFilter(ACTION_RIDE_APP_OPENED),
            RECEIVER_EXPORTED,
        )
        registerReceiver(
            rideStopReceiver,
            IntentFilter(ACTION_RIDE_STOP),
            RECEIVER_EXPORTED,
        )
        bindService(
            Intent(this, MagicshineControlService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        karooSystem.connect { connected ->
            if (connected) {
                Log.d(TAG, "KarooSystem connected")
                karooConnected = true
                karooSystem.dispatch(RequestBluetooth(extension))
                lightService?.onExtensionReadyFromBoundClient()
                lightService?.let { service ->
                    LightFieldState.set(this, LightFieldState.STATUS_IDLE)
                    lightLifecycleHandler?.shutdownHandling()
                    lightLifecycleHandler = LightLifecycleHandler(this, karooSystem, service).also {
                        it.startHandling()
                        if (pendingRideOpen) {
                            it.ensureConnectedNow("pending-ride-open")
                        } else {
                            it.ensureConnectedNow("extension-ready")
                        }
                    }
                }
            } else {
                Log.d(TAG, "KarooSystem disconnected")
                karooConnected = false
            }
        }
    }

    override fun onDestroy() {
        lightLifecycleHandler?.shutdownHandling()
        lightLifecycleHandler = null
        runCatching { unregisterReceiver(rideAppOpenedReceiver) }
        runCatching { unregisterReceiver(rideStopReceiver) }
        lightService?.let { service ->
            service.unregisterListener(serviceListener)
            service.stopRepeatingCommand()
            service.disconnect()
            service.stopDiscovery()
        }
        LightFieldState.set(this, LightFieldState.STATUS_DISCONNECTED)
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        when (MagicshineAction.fromActionId(actionId)) {
            MagicshineAction.OFF -> lightService?.send("DE14A20101010100000000000000000000BB0DED")
            MagicshineAction.LEVEL_10 -> lightService?.send("DE14A2010101010A000000000000000000BB07ED")
            MagicshineAction.LEVEL_100 -> lightService?.send("DE14A20101010160000000000000000000BB6DED")
            null -> Unit
        }
    }

    private fun handleControllerStatus(status: String) {
        Log.d(TAG, "ControllerStatus=$status")
        lastControllerStatus = status
        publishDerivedFieldState()
    }

    private fun handleConnectionStatus(status: String) {
        Log.d(TAG, "ConnectionStatus=$status")
        lastConnectionStatus = status
        if (status in setOf("disconnected", "no device", "fehler")) {
            LightActionReceiver.setToggleEnabled(this, false)
        }
        publishDerivedFieldState()
    }

    private fun publishDerivedFieldState() {
        val fieldStatus = when (lastConnectionStatus) {
            "connected" -> LightFieldState.STATUS_CONNECTED
            "connecting" -> LightFieldState.STATUS_CONNECTING
            "no device" -> LightFieldState.STATUS_NO_DEVICE
            "fehler" -> LightFieldState.STATUS_ERROR
            else -> when (lastControllerStatus) {
                "found" -> LightFieldState.STATUS_FOUND
                "searching" -> LightFieldState.STATUS_SEARCHING
                "error" -> LightFieldState.STATUS_ERROR
                else -> LightFieldState.STATUS_DISCONNECTED
            }
        }
        LightFieldState.set(this, fieldStatus)
    }
}
