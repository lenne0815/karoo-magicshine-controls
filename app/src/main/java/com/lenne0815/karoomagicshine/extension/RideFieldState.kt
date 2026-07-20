package com.lenne0815.karoomagicshine.extension

import android.content.Context

object RideFieldState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_BATTERY_STATUS = "ride_field_battery_status"
    private const val LEGACY_PREF_BATTERY_PERCENT = "ride_field_battery_percent"
    private const val PREF_FLASH_UNTIL_MS = "ride_field_flash_until_ms"

    fun batteryStatus(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_BATTERY_STATUS, null)
    }

    fun setBatteryStatus(context: Context, status: String) {
        val normalized = status.takeIf {
            it == "HIGH" || it == "MID" || it == "LOW" ||
                it.removeSuffix("%").toIntOrNull()?.let { value -> value in 0..100 } == true
        }
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_PREF_BATTERY_PERCENT)
        if (normalized == null) editor.remove(PREF_BATTERY_STATUS) else editor.putString(PREF_BATTERY_STATUS, normalized)
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
