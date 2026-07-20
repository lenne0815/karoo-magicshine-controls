package com.lenne0815.magicshinesniffer

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth

class MagicshineSnifferKarooExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    companion object {
        const val EXTENSION_ID = "karoo-magicshine-sniffer"
        private const val TAG = "MagicshineSnifferExt"
    }

    private val karooSystem by lazy { KarooSystemService(this) }

    override val types = emptyList<DataTypeImpl>()

    override fun onCreate() {
        super.onCreate()
        runCatching {
            karooSystem.connect { connected ->
                Log.i(TAG, "KarooSystem connected=$connected")
                if (connected) karooSystem.dispatch(RequestBluetooth(extension))
            }
        }.onFailure { Log.w(TAG, "Unable to request Bluetooth", it) }
    }

    override fun onDestroy() {
        runCatching { karooSystem.dispatch(ReleaseBluetooth(extension)) }
        runCatching { karooSystem.disconnect() }
        super.onDestroy()
    }
}
