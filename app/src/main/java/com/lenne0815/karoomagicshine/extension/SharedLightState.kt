package com.lenne0815.karoomagicshine.extension

import android.content.Context

object SharedLightState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_OUTPUT_TARGET = "shared_output_target"
    private const val PREF_LEVEL_PERCENT = "shared_level_percent"
    private const val PREF_LAST_OUTPUT_TARGET = "shared_last_output_target"
    private const val PREF_LAST_LEVEL_PERCENT = "shared_last_level_percent"

    enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
    }

    data class Snapshot(
        val outputTarget: OutputTarget,
        val levelPercent: Int?,
        val lastOnTarget: OutputTarget,
        val lastOnLevelPercent: Int?,
    )

    fun get(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val target = runCatching {
            OutputTarget.valueOf(
                prefs.getString(PREF_OUTPUT_TARGET, OutputTarget.OFF.name) ?: OutputTarget.OFF.name,
            )
        }.getOrDefault(OutputTarget.OFF)
        val lastTarget = runCatching {
            OutputTarget.valueOf(
                prefs.getString(PREF_LAST_OUTPUT_TARGET, OutputTarget.LOW.name) ?: OutputTarget.LOW.name,
            )
        }.getOrDefault(OutputTarget.LOW)
        val level = if (prefs.contains(PREF_LEVEL_PERCENT)) prefs.getInt(PREF_LEVEL_PERCENT, 0) else null
        val lastLevel = if (prefs.contains(PREF_LAST_LEVEL_PERCENT)) prefs.getInt(PREF_LAST_LEVEL_PERCENT, 0) else null
        return Snapshot(
            outputTarget = target,
            levelPercent = level?.takeIf { it in setOf(25, 50, 75, 100) },
            lastOnTarget = if (lastTarget == OutputTarget.OFF) OutputTarget.LOW else lastTarget,
            lastOnLevelPercent = lastLevel?.takeIf { it in setOf(25, 50, 75, 100) } ?: 100,
        )
    }

    fun set(context: Context, outputTarget: OutputTarget, levelPercent: Int?) {
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_OUTPUT_TARGET, outputTarget.name)

        if (levelPercent != null) {
            editor.putInt(PREF_LEVEL_PERCENT, levelPercent)
        } else {
            editor.remove(PREF_LEVEL_PERCENT)
        }

        if (outputTarget != OutputTarget.OFF) {
            editor.putString(PREF_LAST_OUTPUT_TARGET, outputTarget.name)
            if (levelPercent != null) {
                editor.putInt(PREF_LAST_LEVEL_PERCENT, levelPercent)
            } else {
                editor.remove(PREF_LAST_LEVEL_PERCENT)
            }
        }

        editor.apply()
    }
}
