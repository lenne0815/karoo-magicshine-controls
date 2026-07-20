package com.lenne0815.karoomagicshine.extension

import android.content.Context

object RideFieldState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_BATTERY_PERCENT = "ride_field_battery_percent"
    private const val PREF_FLASH_UNTIL_MS = "ride_field_flash_until_ms"

    fun batteryPercent(context: Context): Int? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_BATTERY_PERCENT)) return null
        return prefs.getInt(PREF_BATTERY_PERCENT, -1).takeIf { it in 0..100 }
    }

    fun setBatteryStatus(context: Context, status: String) {
        val percent = status.removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
        if (percent == null) editor.remove(PREF_BATTERY_PERCENT) else editor.putInt(PREF_BATTERY_PERCENT, percent)
        editor.apply()
    }

    fun startFlash(context: Context, durationMs: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_FLASH_UNTIL_MS, System.currentTimeMillis() + durationMs)
            .apply()
    }

    fun stopFlash(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_FLASH_UNTIL_MS)
            .apply()
    }

    fun isFlashing(context: Context): Boolean {
        val until = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_FLASH_UNTIL_MS, 0L)
        return until > System.currentTimeMillis()
    }
}
