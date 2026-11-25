package com.atlanyuey.aenht

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.GalleryItem
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.core.ReadMode
import com.atlanyuey.aenht.screens.ReaderPage
import com.atlanyuey.aenht.screens.ReaderScreen
import com.atlanyuey.aenht.screens.ReaderSource
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A long press on a page: its menu, with Reload Page (that page loads again). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ReaderPageMenuTest {
    @get:Rule val compose = createComposeRule()

    private fun open(mode: ReadMode): ReaderScreen {
        if (Net.interceptors.isEmpty()) Net.interceptors.add(FakeApi)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(FakeImages) }.build())
        AppSettings.readMode = mode
        val reader = ReaderScreen(ReaderSource(
            500001, "Sample Gallery", (0 until 12).map { ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
            "https://nhentai.net/g/500001/", GallerySummary.from(GalleryItem(500001, numPages = 12)),
        ), 3)
        val tabs = Tabs().also { it.home.push(reader) }
        compose.setContent { AppTheme { RootView(mutableStateOf<GalleryLink?>(null), tabs, 0) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        return reader
    }

    @Suppress("UNCHECKED_CAST")
    private fun reloads(reader: ReaderScreen) = ReaderScreen::class.java.getDeclaredField("pageRetries").let {
        it.isAccessible = true
        it.get(reader) as SnapshotStateMap<Int, Int>
    }

    @Test fun aLongPressOnAPageReloadsIt() {
        try {
            val reader = open(ReadMode.LeftToRight)
            compose.onRoot().performTouchInput { longClick(center) }
            compose.waitUntil(3000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save Page").assertExists()
            compose.onNodeWithText("Reload Page").performClick()
            compose.waitForIdle()
            assertEquals("page 4 loads again", 1, reloads(reader)[3])
        } finally { AppSettings.readMode = ReadMode.LeftToRight }
    }

    @Test fun inTheStripThePageHeldIsReloaded() {
        try {
            val reader = open(ReadMode.Vertical)
            compose.onRoot().performTouchInput { longClick(center) }
            compose.waitUntil(3000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reload Page").performClick()
            compose.waitForIdle()
            assertEquals(1, reloads(reader).size)
        } finally { AppSettings.readMode = ReadMode.LeftToRight }
    }

    private fun page(reader: ReaderScreen) = ReaderScreen::class.java.getDeclaredField("page\$delegate").let {
        it.isAccessible = true
        (it.get(reader) as androidx.compose.runtime.MutableIntState).intValue
    }

    /** The menu opens where the finger is: its nearest edge is close to it. */
    private fun assertMenuNear(at: androidx.compose.ui.geometry.Offset) {
        val menu = compose.onNodeWithText("Reload Page").fetchSemanticsNode().boundsInRoot
        val dy = when {
            at.y < menu.top -> menu.top - at.y
            at.y > menu.bottom -> at.y - menu.bottom
            else -> 0f
        }
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertTrue("menu $menu near the finger $at", dy < 200 * density)
    }

    @Test fun aLongPressOnTheEdgeDoesNotTurnThePage() {
        try {
            val reader = open(ReadMode.LeftToRight)
            var at = androidx.compose.ui.geometry.Offset.Zero
            // The right edge turns on a tap; held, it's the menu only.
            compose.onRoot().performTouchInput {
                at = androidx.compose.ui.geometry.Offset(width * 0.9f, height * 0.25f)
                longClick(at)
            }
            compose.waitUntil(3000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals("still page 4", 3, page(reader))
            assertMenuNear(at)
        } finally { AppSettings.readMode = ReadMode.LeftToRight }
    }

    @Test fun inTheStripTheMenuOpensAtTheFinger() {
        try {
            open(ReadMode.Vertical)
            var at = androidx.compose.ui.geometry.Offset.Zero
            compose.onRoot().performTouchInput {
                at = androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.8f)
                longClick(at)
            }
            compose.waitUntil(3000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
            assertMenuNear(at)
        } finally { AppSettings.readMode = ReadMode.LeftToRight }
    }
}
