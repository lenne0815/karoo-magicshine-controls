package com.lenne0815.karoomagicshine

enum class MagicshineModule {
    MODULE_1,
    MODULE_2,
}

enum class MagicshineMode {
    STEADY,
    SOS,
    BLITZ,
}

object MagicshineProtocol {
    fun buildPresetFrame(module: MagicshineModule, level: Int): String {
        val normalized = level.coerceIn(0, 100)
        return when (module) {
            MagicshineModule.MODULE_1 -> when (normalized) {
                0 -> "DE14A20101010100000000000000000000BB0DED"
                25 -> "DE14A20101010114000150000000000000BB48ED"
                50 -> "DE14A2010101013C000150000000000000BB60ED"
                75 -> buildBrightnessFrame(module, 75)
                else -> "DE14A20101010163000150000000000000BB3FED"
            }
            MagicshineModule.MODULE_2 -> when (normalized) {
                0 -> "DE14A20101010100000000000000000000BB0DED"
                25 -> "DE14A2010200010A010114000000000000BB11ED"
                50 -> "DE14A2010200010A010132000000000000BB37ED"
                75 -> buildBrightnessFrame(module, 75)
                else -> "DE14A2010200010A010164000000000000BB61ED"
            }
        }
    }

    fun buildBrightnessFrame(module: MagicshineModule, percent: Int): String {
        val value = percent.coerceIn(0, 100)
        return when (module) {
            MagicshineModule.MODULE_1 -> {
                val checksum = value xor 0x5C
                "DE14A201010101%02X000150000000000000BB%02XED".format(value, checksum)
            }
            MagicshineModule.MODULE_2 -> buildModule2Frame(modeCode = 0x01, value = value)
        }
    }

    fun buildModeFrame(module: MagicshineModule, mode: MagicshineMode): String {
        return when (module) {
            MagicshineModule.MODULE_1 -> {
                when (mode) {
                    MagicshineMode.STEADY -> "DE14A2010101010A000150000000000000BB56ED"
                    MagicshineMode.SOS -> "DE14A20101010263000150000000000000BB3CED"
                    MagicshineMode.BLITZ -> "DE14A20101010363000150000000000000BB3DED"
                }
            }
            MagicshineModule.MODULE_2 -> {
                val modeCode = when (mode) {
                    MagicshineMode.STEADY -> 0x01
                    MagicshineMode.SOS -> 0x02
                    MagicshineMode.BLITZ -> 0x03
                }
                buildModule2Frame(modeCode = modeCode, value = 0x64)
            }
        }
    }

    fun parseBatteryPercent(frameHex: String): Int? {
        val clean = frameHex.uppercase()
        if (!clean.startsWith("DE13B4") || clean.length < 16) return null
        val payload = clean.removePrefix("DE13B4").dropLast(4)
        val bytes = payload.chunked(2).mapNotNull { it.toIntOrNull(16) }
        return bytes.getOrNull(5)?.takeIf { it in 0..100 }
    }

    fun parseTemperatureCelsius(frameHex: String): Int? {
        val clean = frameHex.uppercase()
        if (!clean.startsWith("DE0DB1")) return null
        val markerIndex = clean.indexOf("1703")
        if (markerIndex == -1 || clean.length < markerIndex + 6) return null
        val temperatureHex = clean.substring(markerIndex + 4, markerIndex + 6)
        val temperature = temperatureHex.toIntOrNull(16) ?: return null
        return temperature.takeIf { it in 0..120 }
    }

    private fun buildModule2Frame(modeCode: Int, value: Int): String {
        val checksum = value xor (modeCode + 0x04)
        return "DE14A2010200010A01%02X%02X000000000000BB%02XED".format(modeCode, value, checksum)
    }
}
