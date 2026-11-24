package com.atlanyuey.aenht

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.screens.GalleryScreen
import com.atlanyuey.aenht.shared.site.GalleryLink
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
import kotlin.random.Random

/**
 * Switching tabs while pages open and close (issue reports: a page stuck
 * half way in after a quick tab switch; crashes after many taps on the tabs).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class NavigationRobustnessTest {
    @get:Rule val compose = createComposeRule()

    /**
     * The sample sites and images: no network, and no image decoded on a
     * worker thread (Robolectric's native graphics could abort the test run there).
     */
    @org.junit.Before fun sampleSites() {
        if (Net.interceptors.isEmpty()) Net.interceptors.add(FakeApi)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(FakeImages) }.build())
    }

    private fun open(tab: Int): Tabs {
        val tabs = Tabs()
        val linked = mutableStateOf<GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linked, tabs, tab) } }
        compose.waitForIdle()
        return tabs
    }

    /** The tab button [label] (the tab bar is drawn last: its text is the last of that name). */
    private fun tap(label: String) {
        val nodes = compose.onAllNodesWithText(label)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
    }

    /** Shows one tab's stack at a time, as RootView does: the other tab's view goes. */
    private fun host(tabs: Tabs, shown: androidx.compose.runtime.MutableIntState) {
        compose.setContent {
            AppTheme {
                androidx.compose.runtime.key(shown.intValue) {
                    com.atlanyuey.aenht.ui.components.NavHost(tabs.all[shown.intValue])
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aTabSwitchDuringAPushDoesNotLeaveThePageHalfWay() {
        val tabs = Tabs()
        val shown = androidx.compose.runtime.mutableIntStateOf(2)
        host(tabs, shown)
        compose.mainClock.autoAdvance = false
        tabs.history.push(GalleryScreen(500001, null))
        advance(50) // Part way in.
        assertTrue("part way in", tabs.history.progress < 1f)
        shown.intValue = 1 // Another tab: the History stack's view goes.
        advance(500)
        shown.intValue = 2
        advance(500)
        compose.waitForIdle()
        assertTrue(tabs.history.top is GalleryScreen)
        assertEquals("the page is all the way in", 1f, tabs.history.progress, 0.001f)
    }

    @Test fun aTabSwitchDuringAPopFinishesIt() {
        val tabs = Tabs()
        val shown = androidx.compose.runtime.mutableIntStateOf(2)
        host(tabs, shown)
        tabs.history.push(GalleryScreen(500001, null))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        tabs.history.pop()
        advance(50)
        assertTrue("part way out", tabs.history.progress < 1f)
        shown.intValue = 1
        advance(500)
        shown.intValue = 2
        advance(500)
        compose.waitForIdle()
        assertEquals("the page is gone, not stuck half way out", 1, tabs.history.stack.size)
        assertEquals(1f, tabs.history.progress, 0.001f)
    }

    @Test fun aPushWhileAPopRunsFinishesThePopFirst() {
        val tabs = Tabs()
        val shown = androidx.compose.runtime.mutableIntStateOf(0)
        host(tabs, shown)
        tabs.home.push(GalleryScreen(500001, null))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        tabs.home.pop()
        advance(30)
        tabs.home.push(GalleryScreen(500002, null))
        advance(1000)
        compose.waitForIdle()
        assertEquals("the first page went, the second is in", 2, tabs.home.stack.size)
        assertEquals(1f, tabs.home.progress, 0.001f)
    }

    @Test fun manyQuickTabTapsWithPagesOpeningAndClosing() {
        val tabs = open(tab = 0)
        compose.mainClock.autoAdvance = false
        val labels = listOf("nhentai", "Favorites", "History", "Me")
        val random = Random(12)
        repeat(300) { step ->
            when (random.nextInt(10)) {
                in 0..5 -> tap(labels[random.nextInt(labels.size)])
                6 -> tabs.all[random.nextInt(4)].push(GalleryScreen(500001 + step % 5, null))
                7 -> tabs.all[random.nextInt(4)].pop()
                else -> tap("Me")
            }
            advance(random.nextLong(5, 120))
        }
        advance(2000)
        compose.waitForIdle()
        for (navigator in tabs.all) {
            assertEquals("every stack settled", 1f, navigator.progress, 0.001f)
        }
    }
}
