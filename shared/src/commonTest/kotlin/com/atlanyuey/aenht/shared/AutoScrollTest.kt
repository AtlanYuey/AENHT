package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.reader.ReaderAutoScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoScrollTest {
    @Test fun oneScreenTakesTheSameTimeAt60And120Hz() {
        for (hz in listOf(60, 120)) {
            val distance = (1..hz * 15).sumOf { ReaderAutoScroll.distance(900.0, 15.0, 1.0 / hz) }
            assertEquals(900.0, distance, 0.00001)
        }
    }

    @Test fun speedUsesViewportRatherThanPageHeightOrDisplayDensity() {
        assertEquals(2.0, ReaderAutoScroll.distance(600.0, 5.0, 1.0 / 60), 0.00001)
        assertEquals(4.0, ReaderAutoScroll.distance(1200.0, 5.0, 1.0 / 60), 0.00001)
        assertEquals(0.5, ReaderAutoScroll.distance(900.0, 60.0, 1.0 / 30), 0.00001)
    }

    @Test fun aStallDoesNotSkipUnreadPanels() {
        assertEquals(3.0, ReaderAutoScroll.distance(900.0, 15.0, 8.0), 0.00001)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertEquals(0.0, ReaderAutoScroll.distance(900.0, 15.0, bad))
            assertEquals(0.0, ReaderAutoScroll.distance(bad, 15.0, 0.016))
        }
    }

    @Test fun oldAndInvalidSettingsStayUsable() {
        assertEquals(5.0, ReaderAutoScroll.secondsPerScreen(-5.0))
        assertEquals(60.0, ReaderAutoScroll.secondsPerScreen(999.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(15.0, ReaderAutoScroll.secondsPerScreen(bad))
        }
    }

    @Test fun loadingBoundaryNeverMovesPastUnreadContent() {
        assertEquals(0.0, ReaderAutoScroll.beforeUnloadedPage(2.0, 900.0, 600.0))
        assertEquals(0.0, ReaderAutoScroll.beforeUnloadedPage(2.0, 900.0, 900.0))
        assertEquals(1.0, ReaderAutoScroll.beforeUnloadedPage(2.0, 900.0, 901.0))
        assertEquals(2.0, ReaderAutoScroll.beforeUnloadedPage(2.0, 900.0, 1000.0))
        assertTrue(ReaderAutoScroll.beforeUnloadedPage(2.0, 900.0, 1000.0).isFinite())
    }
}
