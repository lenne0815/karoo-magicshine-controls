package com.lenne0815.karoomagicshine.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState

abstract class RideHandler(
    private val karooSystem: KarooSystemService,
) {
    private var consumerId: String? = null
    protected var rideState: RideState = RideState.Idle

    fun start() {
        if (consumerId != null) return
        consumerId = karooSystem.addConsumer { newRideState: RideState ->
            val oldRideState = rideState
            rideState = newRideState

            when {
                newRideState is RideState.Recording && oldRideState is RideState.Idle -> onRideStart()
                newRideState is RideState.Recording && oldRideState is RideState.Paused -> onRideResume()
                newRideState is RideState.Paused && oldRideState is RideState.Recording -> onRidePause()
                newRideState is RideState.Idle && (oldRideState is RideState.Recording || oldRideState is RideState.Paused) -> onRideEnd()
            }
        }
    }

    fun stop() {
        consumerId?.let(karooSystem::removeConsumer)
        consumerId = null
    }

    protected open fun onRideStart() {}

    protected open fun onRidePause() {}

    protected open fun onRideResume() {}

    protected open fun onRideEnd() {}
}
