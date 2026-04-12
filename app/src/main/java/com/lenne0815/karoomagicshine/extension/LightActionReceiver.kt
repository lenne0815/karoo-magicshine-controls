package com.lenne0815.karoomagicshine.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LightActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val frame = when (intent.action) {
            ACTION_OFF -> "DE14A20101010100000000000000000000BB0DED"
            ACTION_10 -> "DE14A2010101010A000000000000000000BB07ED"
            ACTION_100 -> "DE14A20101010160000000000000000000BB6DED"
            else -> null
        } ?: return

        MagicshineBleController.getShared(context.applicationContext).send(frame)
    }

    companion object {

        const val ACTION_OFF = "com.lenne0815.karoomagicshine.action.LIGHT_OFF"
        const val ACTION_10 = "com.lenne0815.karoomagicshine.action.LIGHT_10"
        const val ACTION_100 = "com.lenne0815.karoomagicshine.action.LIGHT_100"
    }
}
