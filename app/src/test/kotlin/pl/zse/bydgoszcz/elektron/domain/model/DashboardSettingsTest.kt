package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSettingsTest {

    @Test
    fun defaultOrderContainsAllCardsVisible() {
        val defaultList = DashboardLayoutUtils.defaultOrder()
        assertEquals(DashboardCardId.entries.size, defaultList.size)
        assertTrue(defaultList.all { it.isVisible })
        assertEquals(DashboardCardId.entries.toSet(), defaultList.map { it.id }.toSet())
    }

    @Test
    fun encodeAndDecodeRoundtrip() {
        val original = listOf(
            DashboardCardItem(DashboardCardId.DUTY, isVisible = true),
            DashboardCardItem(DashboardCardId.COUNTDOWN, isVisible = false),
            DashboardCardItem(DashboardCardId.GRADES, isVisible = true)
        )
        val encoded = DashboardLayoutUtils.encode(original)
        val decoded = DashboardLayoutUtils.decode(encoded)

        assertEquals(DashboardCardId.DUTY, decoded[0].id)
        assertTrue(decoded[0].isVisible)

        assertEquals(DashboardCardId.COUNTDOWN, decoded[1].id)
        assertFalse(decoded[1].isVisible)

        assertEquals(DashboardCardId.GRADES, decoded[2].id)
        assertTrue(decoded[2].isVisible)

        // Missing cards should be automatically appended with isVisible = true
        assertEquals(DashboardCardId.entries.size, decoded.size)
    }

    @Test
    fun decodeHandlesCorruptedOrEmptyInput() {
        val emptyDecoded = DashboardLayoutUtils.decode("")
        assertEquals(DashboardLayoutUtils.defaultOrder(), emptyDecoded)

        val nullDecoded = DashboardLayoutUtils.decode(null)
        assertEquals(DashboardLayoutUtils.defaultOrder(), nullDecoded)

        val corruptedDecoded = DashboardLayoutUtils.decode("UNKNOWN:1;GARBAGE;:::;DUTY:0")
        assertEquals(DashboardCardId.DUTY, corruptedDecoded[0].id)
        assertFalse(corruptedDecoded[0].isVisible)
        assertEquals(DashboardCardId.entries.size, corruptedDecoded.size)
    }
}
