package com.lenne0815.karoomagicshine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MagicshineProtocolTest {
    private val highBatteryFrame = "DE13B40000000000640000000000000000C3ED"
    private val midBatteryFrame = "DE13B4000000000032000000000000000095ED"
    private val lowBatteryFrame = "DE13B400000000001E0000000000000000B9ED"

    @Test
    fun preservesPercentageForDevicesWithContinuousTelemetry() {
        assertEquals("100%", MagicshineProtocol.parseBatteryStatus(highBatteryFrame, "M1-B0 TEST"))
    }

    @Test
    fun mapsObservedEvo1700ValuesToIndicators() {
        assertEquals("HIGH", MagicshineProtocol.parseBatteryStatus(highBatteryFrame, "M2-B0 EVO_1700"))
        assertEquals("MID", MagicshineProtocol.parseBatteryStatus(midBatteryFrame, "M2-BO EVO_1700"))
        assertEquals("LOW", MagicshineProtocol.parseBatteryStatus(lowBatteryFrame, "M2-B0 EVO_1700"))
    }

    @Test
    fun rejectsUnobservedCoarseValue() {
        val unknownFrame = "DE13B400000000002800000000000000008FED"
        assertNull(MagicshineProtocol.parseBatteryStatus(unknownFrame, "M2-B0 EVO_1700"))
    }
}
