package com.lenne0815.karoomagicshine.extension

internal enum class LightTile { TOGGLE, FLASH, BATTERY, APP }

internal enum class RideFieldLayout(val typeId: String, val tiles: List<LightTile>) {
    COMBINED("DATATYPE_LIGHT_CONTROLS", LightTile.entries),
    TOGGLE("DATATYPE_LIGHT_TOGGLE", listOf(LightTile.TOGGLE)),
    FLASH("DATATYPE_LIGHT_FLASH", listOf(LightTile.FLASH)),
    BATTERY("DATATYPE_LIGHT_BATTERY", listOf(LightTile.BATTERY)),
    APP("DATATYPE_LIGHT_APP", listOf(LightTile.APP));

    fun usesLamp(preview: Boolean) = !preview && this != APP
}
