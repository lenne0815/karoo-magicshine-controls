package com.lenne0815.karoomagicshine.extension

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideFieldTest {
    @Test fun existingFieldAndIndependentTiles() {
        assertEquals("DATATYPE_LIGHT_CONTROLS", RideFieldLayout.COMBINED.typeId)
        val order = listOf(LightTile.TOGGLE, LightTile.FLASH, LightTile.BATTERY, LightTile.APP)
        assertEquals(order, RideFieldLayout.COMBINED.tiles)
        assertEquals(order.map { listOf(it) }, RideFieldLayout.entries.drop(1).map { it.tiles })
        assertEquals(5, RideFieldLayout.entries.map { it.typeId }.distinct().size)
    }

    @Test fun previewsAndLauncherDoNotRequestBluetooth() {
        for (field in RideFieldLayout.entries) {
            assertFalse(field.usesLamp(preview = true))
            assertEquals(field != RideFieldLayout.APP, field.usesLamp(preview = false))
        }
    }

    @Test fun overlappingViewsAndPageHandoffDoNotDisconnect() = runTest {
        val views = RideFieldVisibility(this)
        var disconnects = 0
        assertTrue(views.shown())
        assertFalse(views.shown())
        views.hidden({ false }) { disconnects++ }
        advanceTimeBy(1000)
        assertEquals(0, disconnects)
        views.hidden({ false }) { disconnects++ }
        advanceTimeBy(200)
        assertFalse(views.shown())
        advanceTimeBy(1000)
        assertEquals(0, disconnects)
        views.hidden({ false }) { disconnects++ }
        advanceTimeBy(1000)
        assertEquals(1, disconnects)
        assertFalse(views.visible)
        assertTrue(views.shown())
    }

    @Test fun openingTheAppBeforeLastFieldExpiresKeepsItsConnection() = runTest {
        val views = RideFieldVisibility(this)
        var appActive = false
        var disconnects = 0
        views.shown()
        views.hidden({ appActive }) { disconnects++ }
        advanceTimeBy(200)
        appActive = true
        advanceTimeBy(1000)
        assertEquals(0, disconnects)
    }
}
