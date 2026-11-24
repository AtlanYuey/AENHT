package com.atlanyuey.aenht

import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.TapZones
import com.atlanyuey.aenht.screens.tapTogglesControls
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.components.GallerySelection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Choosing several galleries (Favorites, History) and Tap the Middle to Show Controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelectionAndTapsTest {
    private fun card(id: Long) = GallerySummary(GalleryKey.nh(id.toInt()), "Gallery $id", null, 0, 0, 10, null)

    @After fun reset() {
        AppSettings.controlsMiddleOnly = false
        AppSettings.tapZones = TapZones.LeftRight
    }

    @Test fun aTapChoosesAndUnchooses() {
        val selection = GallerySelection()
        val a = card(1)
        val b = card(2)
        selection.toggle(a)
        selection.toggle(b)
        assertTrue(a in selection && b in selection)
        selection.toggle(a)
        assertFalse(a in selection)
        assertEquals(listOf(b.key.storageKey), selection.keys.toList())
    }

    @Test fun selectAllThenNoneAndNoDuplicates() {
        val selection = GallerySelection()
        val items = listOf(card(1), card(2), card(2), card(3))
        selection.toggle(items[0])
        selection.toggleAll(items)
        assertEquals("all, once each", 3, selection.keys.size)
        selection.toggleAll(items)
        assertTrue("all chosen: none", selection.keys.isEmpty())
        selection.toggleAll(emptyList())
        assertTrue(selection.keys.isEmpty())
    }

    @Test fun everyTapTogglesTheControlsByDefault() {
        for (x in listOf(0.05f, 0.5f, 0.95f)) {
            assertTrue(tapTogglesControls(x, 0.5f, paged = true))
            assertTrue(tapTogglesControls(x, 0.5f, paged = false))
        }
    }

    @Test fun middleOnlyLeavesTheSidesAlone() {
        AppSettings.controlsMiddleOnly = true
        // Left and right zones; the continuous modes are always left and right.
        assertFalse(tapTogglesControls(0.1f, 0.5f, paged = true))
        assertFalse(tapTogglesControls(0.9f, 0.5f, paged = false))
        assertTrue(tapTogglesControls(0.5f, 0.1f, paged = true))
        assertTrue(tapTogglesControls(0.5f, 0.5f, paged = false))
        // Top and bottom zones (paged): the top and bottom are the sides.
        AppSettings.tapZones = TapZones.TopBottom
        assertFalse(tapTogglesControls(0.5f, 0.1f, paged = true))
        assertTrue(tapTogglesControls(0.1f, 0.5f, paged = true))
        assertFalse("scrolling: left and right still", tapTogglesControls(0.1f, 0.5f, paged = false))
    }
}
