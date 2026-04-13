package com.lenne0815.karoomagicshine.extension

import android.content.Context

object AppUiState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_APP_UI_ACTIVE = "app_ui_active"

    fun isActive(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_APP_UI_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_APP_UI_ACTIVE, active)
            .apply()
    }
}
