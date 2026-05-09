package com.lenne0815.karoomagicshine.extension

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState

abstract class RideHandler(
    private val karooSystem: KarooSystemService,
) {
    companion object {
        private const val TAG = "MagicshineExt"
    }

    private var consumerId: String? = null
    protected var rideState: RideState = RideState.Idle

    fun start() {
        if (consumerId != null) return
        runCatching {
            karooSystem.addConsumer { newRideState: RideState ->
                val oldRideState = rideState
                rideState = newRideState

                when {
                    newRideState is RideState.Recording && oldRideState is RideState.Idle -> onRideStart()
                    newRideState is RideState.Recording && oldRideState is RideState.Paused -> onRideResume()
                    newRideState is RideState.Paused && oldRideState is RideState.Recording -> onRidePause()
                    newRideState is RideState.Idle && (oldRideState is RideState.Recording || oldRideState is RideState.Paused) -> onRideEnd()
                }
            }
        }.onSuccess { id ->
            consumerId = id
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to add ride-state consumer", throwable)
        }
    }

    fun stop() {
        val id = consumerId ?: return
        consumerId = null
        runCatching {
            karooSystem.removeConsumer(id)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to remove ride-state consumer", throwable)
        }
    }

    protected open fun onRideStart() {}

    protected open fun onRidePause() {}

    protected open fun onRideResume() {}

    protected open fun onRideEnd() {}
}
