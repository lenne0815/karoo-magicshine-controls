package com.lenne0815.karoomagicshine.extension

import android.content.Context

object LightFieldState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_EXTENSION_FIELD_STATUS = "extension_field_status"

    const val STATUS_IDLE = "idle"
    const val STATUS_SEARCHING = "searching"
    const val STATUS_FOUND = "found"
    const val STATUS_CONNECTING = "connecting"
    const val STATUS_CONNECTED = "connected"
    const val STATUS_DISCONNECTED = "disconnected"
    const val STATUS_NO_DEVICE = "no_device"
    const val STATUS_ERROR = "error"

    fun get(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_EXTENSION_FIELD_STATUS, STATUS_IDLE)
            ?: STATUS_IDLE

    fun set(context: Context, status: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_EXTENSION_FIELD_STATUS, status)
            .apply()
    }
}
