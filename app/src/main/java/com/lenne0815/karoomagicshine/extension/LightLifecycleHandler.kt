package com.lenne0815.karoomagicshine.extension

import android.content.Context
import android.util.Log
import io.hammerhead.karooext.KarooSystemService

class LightLifecycleHandler(
    private val context: Context,
    karooSystem: KarooSystemService,
    private val service: MagicshineControlService,
) : RideHandler(karooSystem) {

    companion object {
        private const val TAG = "MagicshineExt"
    }

    private var started = false
    private var connectedWanted = false

    fun startHandling() {
        if (started) return
        started = true
        super.start()
    }

    fun ensureConnectedNow(reason: String) {
        ensureConnected(reason)
    }

    fun disconnectNow(reason: String) {
        disconnect(reason)
    }

    fun shutdownHandling() {
        if (!started) return
        started = false
        super.stop()
        connectedWanted = false
        service.stopRepeatingCommand()
        service.disconnect()
    }

    override fun onRideStart() {
        Log.d(TAG, "Ride started")
        ensureConnected("ride-start")
    }

    override fun onRideResume() {
        Log.d(TAG, "Ride resumed")
        ensureConnected("ride-resume")
    }

    override fun onRideEnd() {
        Log.d(TAG, "Ride ended")
        disconnect("ride-end")
    }

    private fun ensureConnected(reason: String) {
        if (AppUiState.isActive(context)) {
            Log.d(TAG, "Skipping ride connect while app UI is active ($reason)")
            return
        }
        service.clearStalePublishedConnectionState()
        val hasLiveConnection = service.hasLiveConnection()
        val hasConnectInFlight = service.hasConnectInFlight()
        if (hasLiveConnection || hasConnectInFlight) {
            connectedWanted = true
            Log.d(TAG, "Connect already wanted ($reason)")
            return
        }
        connectedWanted = true
        Log.d(TAG, "Ensuring light connection ($reason)")
        service.ensureConnectedFromExtension()
    }

    private fun disconnect(reason: String) {
        if (!connectedWanted) {
            Log.d(TAG, "Disconnect ignored, not active ($reason)")
            return
        }
        connectedWanted = false
        Log.d(TAG, "Disconnecting light ($reason)")
        service.stopRepeatingCommand()
        service.disconnect()
    }
}
