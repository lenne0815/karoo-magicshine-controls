package com.lenne0815.karoomagicshine.extension

import android.content.Context

object SharedLightState {
    private const val PREFS_NAME = "magicshine_prefs"
    private const val PREF_OUTPUT_TARGET = "shared_output_target"
    private const val PREF_LEVEL_PERCENT = "shared_level_percent"
    private const val PREF_LAST_OUTPUT_TARGET = "shared_last_output_target"
    private const val PREF_LAST_LEVEL_PERCENT = "shared_last_level_percent"
    private const val PREF_MODE = "shared_mode"
    private const val PREF_LAST_MODE = "shared_last_mode"

    enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
    }

    enum class Mode {
        STEADY,
        SOS,
        BLITZ,
    }

    data class Snapshot(
        val outputTarget: OutputTarget,
        val levelPercent: Int?,
        val mode: Mode,
        val lastOnTarget: OutputTarget,
        val lastOnLevelPercent: Int?,
        val lastOnMode: Mode,
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
        val mode = readMode(prefs.getString(PREF_MODE, Mode.STEADY.name))
        val lastMode = readMode(prefs.getString(PREF_LAST_MODE, Mode.STEADY.name))
        return Snapshot(
            outputTarget = target,
            levelPercent = level?.takeIf { it in setOf(25, 50, 75, 100) },
            mode = if (target == OutputTarget.OFF) Mode.STEADY else mode,
            lastOnTarget = if (lastTarget == OutputTarget.OFF) OutputTarget.LOW else lastTarget,
            lastOnLevelPercent = lastLevel?.takeIf { it in setOf(25, 50, 75, 100) } ?: 100,
            lastOnMode = lastMode,
        )
    }

    fun set(
        context: Context,
        outputTarget: OutputTarget,
        levelPercent: Int?,
        mode: Mode = Mode.STEADY,
    ) {
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_OUTPUT_TARGET, outputTarget.name)
            .putString(PREF_MODE, if (outputTarget == OutputTarget.OFF) Mode.STEADY.name else mode.name)

        if (levelPercent != null) {
            editor.putInt(PREF_LEVEL_PERCENT, levelPercent)
        } else {
            editor.remove(PREF_LEVEL_PERCENT)
        }

        if (outputTarget != OutputTarget.OFF) {
            editor.putString(PREF_LAST_OUTPUT_TARGET, outputTarget.name)
            editor.putString(PREF_LAST_MODE, mode.name)
            if (levelPercent != null) {
                editor.putInt(PREF_LAST_LEVEL_PERCENT, levelPercent)
            } else {
                editor.remove(PREF_LAST_LEVEL_PERCENT)
            }
        }

        editor.apply()
    }

    private fun readMode(value: String?): Mode =
        runCatching { Mode.valueOf(value ?: Mode.STEADY.name) }.getOrDefault(Mode.STEADY)
}
