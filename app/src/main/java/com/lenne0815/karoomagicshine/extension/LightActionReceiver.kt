package com.lenne0815.karoomagicshine.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LightActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = when (intent.action) {
            ACTION_TOGGLE_100 ->
                Intent(context, MagicshineControlService::class.java)
                    .setAction(MagicshineControlService.ACTION_TOGGLE_100)
            else -> null
        } ?: return

        context.startService(serviceIntent)
    }

    companion object {
        private const val PREFS_NAME = "magicshine_prefs"
        private const val PREF_EXTENSION_TOGGLE_100 = "extension_toggle_100"

        const val ACTION_TOGGLE_100 = "com.lenne0815.karoomagicshine.action.LIGHT_TOGGLE_100"

        fun isToggleEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_EXTENSION_TOGGLE_100, false)

        fun setToggleEnabled(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_EXTENSION_TOGGLE_100, enabled)
                .apply()
        }
    }
}
