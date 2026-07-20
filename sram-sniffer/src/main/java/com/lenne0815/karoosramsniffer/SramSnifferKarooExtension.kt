package com.lenne0815.karoosramsniffer

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RequestAnt

class SramSnifferKarooExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    companion object {
        const val EXTENSION_ID = "karoo-sram-sniffer"
        private const val TAG = "SramSnifferExt"
    }

    private val karooSystem by lazy { KarooSystemService(this) }

    override val types = emptyList<DataTypeImpl>()

    override fun onCreate() {
        super.onCreate()
        runCatching {
            karooSystem.connect { connected ->
                Log.i(TAG, "KarooSystem connected=$connected")
                if (connected) {
                    requestAnt("extension-connected")
                }
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to connect KarooSystem", throwable)
        }
    }

    override fun onDestroy() {
        runCatching { karooSystem.disconnect() }
        super.onDestroy()
    }

    private fun requestAnt(reason: String) {
        runCatching {
            Log.i(TAG, "Requesting Karoo ANT access ($reason)")
            karooSystem.dispatch(RequestAnt(extension))
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to request Karoo ANT access ($reason)", throwable)
        }
    }
}
