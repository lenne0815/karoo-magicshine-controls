package com.lenne0815.karoomagicshine.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth

class MagicshineKarooExtension : KarooExtension("karoo-magicshine-controls", "1.0") {

    private val karooSystem by lazy { KarooSystemService(this) }
    private val lightController by lazy { MagicshineBleController.getShared(this) }

    override val types by lazy {
        listOf(
            LightControlsDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            if (connected) {
                karooSystem.dispatch(RequestBluetooth(extension))
            }
        }
    }

    override fun onDestroy() {
        lightController.stopDiscovery()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        when (MagicshineAction.fromActionId(actionId)) {
            MagicshineAction.OFF -> lightController.send("DE14A20101010100000000000000000000BB0DED")
            MagicshineAction.LEVEL_10 -> lightController.send("DE14A2010101010A000000000000000000BB07ED")
            MagicshineAction.LEVEL_100 -> lightController.send("DE14A20101010160000000000000000000BB6DED")
            null -> Unit
        }
    }
}
