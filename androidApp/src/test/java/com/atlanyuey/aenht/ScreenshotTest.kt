package com.atlanyuey.aenht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.pinch
import com.atlanyuey.aenht.core.ReadMode
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.GalleryListStyle
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.core.ThemeMode
import com.atlanyuey.aenht.screens.GalleryScreen
import com.atlanyuey.aenht.screens.ReaderScreen
import com.atlanyuey.aenht.screens.ReaderSource
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import org.junit.Assert.assertTrue

/** Renders the main screens with sample data (no network) to build/screens/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        if (Net.interceptors.isEmpty()) Net.interceptors.add(FakeApi)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(FakeImages) }.build())
        AppSettings.theme = ThemeMode.Light
        AppSettings.listStyle = GalleryListStyle.Waterfall
        AppSettings.homeSite = com.atlanyuey.aenht.shared.site.Site.NHENTAI
        AppSettings.nhentaiEnabled = true
        AppSettings.ehentaiEnabled = true
        // The sites' rate limits use the real clock and are shared by all the tests:
        // each starts with none of the requests of the ones before (not a lower limit).
        kotlinx.coroutines.runBlocking {
            com.atlanyuey.aenht.core.NHClient.api.limiter.clear()
            com.atlanyuey.aenht.core.EhClient.api.limiter.clear()
        }
        com.atlanyuey.aenht.core.RateNotice.clear()
    }

    // MARK: Two sites

    @Test fun ehHome() {
        AppSettings.homeSite = com.atlanyuey.aenht.shared.site.Site.EHENTAI
        shoot("40_eh_home", settle = 2500)
    }

    @Test fun ehHomeList() {
        AppSettings.homeSite = com.atlanyuey.aenht.shared.site.Site.EHENTAI
        AppSettings.listStyle = GalleryListStyle.List
        shoot("41_eh_home_list", settle = 2500)
    }

    @Test fun ehGallery() {
        val tabs = Tabs()
        tabs.home.push(com.atlanyuey.aenht.screens.EhGalleryScreen(4321, "1a2b3c4d5e", null))
        shoot("42_eh_gallery", tabs, settle = 2500)
    }

    @Test fun mixedHistory() {
        com.atlanyuey.aenht.core.HistoryStore.clear()
        com.atlanyuey.aenht.core.HistoryStore.record(com.atlanyuey.aenht.shared.site.GallerySummary.from(
            com.atlanyuey.aenht.shared.eh.EhGallerySummary(4321, "1a2b3c4d5e", "[Circle] An E-Hentai Gallery", com.atlanyuey.aenht.shared.eh.EhCategory.MANGA,
                "https://ehgt.org/w/01/a.webp", 250, 350, "2026-11-13 21:58", 30, "someone", 4.5, emptyList()),
        ), page = 8)
        com.atlanyuey.aenht.core.HistoryStore.record(com.atlanyuey.aenht.core.GalleryItem(500003, mediaId = "103", englishTitle = "Rainy Day Café", thumbnail = "galleries/103/thumb.jpg", thumbnailWidth = 350, thumbnailHeight = 500, numPages = 20, tagIds = listOf(6346)), "Rainy Day Café", 20, 3)
        AppSettings.historySite = com.atlanyuey.aenht.core.SiteChoice.All
        shoot("43_history_mixed", Tabs(), tab = 2)
    }

    @Test fun sites() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.SitesScreen())
        shoot("44_sites", tabs, tab = 3)
    }

    @Test fun accounts() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.AccountsScreen())
        shoot("45_accounts", tabs, tab = 3)
    }

    private fun shoot(
        name: String,
        tabs: Tabs = Tabs(),
        tab: Int = 0,
        settle: Long = 1500,
        overlays: com.atlanyuey.aenht.ui.components.OverlayController = com.atlanyuey.aenht.ui.components.OverlayController(),
    ) {
        val linkedGallery = mutableStateOf<com.atlanyuey.aenht.shared.site.GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linkedGallery, tabs, tab, overlays) } }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(settle)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun home() = shoot("01_home")

    @Test fun homeDark() {
        AppSettings.theme = ThemeMode.Dark
        shoot("02_home_dark")
    }

    @Test fun homeCompact() {
        AppSettings.listStyle = GalleryListStyle.Compact
        shoot("03_home_compact")
    }

    @Test fun gallery() {
        val tabs = Tabs()
        tabs.home.push(GalleryScreen(500001, null))
        shoot("04_gallery", tabs)
    }

    @Test fun galleryOpensOutOfItsCover() {
        // Home with the card, then its page: it grows out of the cover, its details fade in once it's there.
        val tabs = Tabs()
        shoot("04a_home_before", tabs)
        compose.mainClock.autoAdvance = false
        // The card "Night Walk" (500001), tapped: its cover flies to the page's cover.
        compose.onAllNodes(androidx.compose.ui.test.hasText("Night Walk", substring = true))[0].performClick()
        assertTrue("the card's cover is where it flies from",
            com.atlanyuey.aenht.ui.components.GalleryCovers.rect(com.atlanyuey.aenht.shared.site.GalleryKey.nh(500001)) != null)
        compose.mainClock.advanceTimeBy(80)
        compose.onRoot().captureRoboImage("build/screens/04b_gallery_zoom_early.png")
        compose.mainClock.advanceTimeBy(120)
        compose.onRoot().captureRoboImage("build/screens/04c_gallery_zoom_mid.png")
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/04d_gallery_zoom_end.png")
    }

    @Test fun reader() {
        val tabs = Tabs()
        val source = ReaderSource(
            500001, "Sample Gallery", (0 until 24).map { com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
            "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
        )
        tabs.home.push(ReaderScreen(source, 3))
        shoot("05_reader", tabs, settle = 500)
        // Left alone for a second, the controls hide.
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/23_reader_idle.png")
    }

    @Test fun readerScrub() {
        // Holding the slider: the page follows the finger, the pages around it show above.
        val tabs = Tabs()
        val source = ReaderSource(
            500001, "Sample Gallery", (0 until 24).map {
                com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f, "https://t.nhentai.net/galleries/9/${it + 1}t.jpg")
            },
            "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
        )
        val reader = ReaderScreen(source, 3)
        tabs.home.push(reader)
        shoot("05b_reader_bar", tabs, settle = 500)
        val slider = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        slider.performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.14f, 0f))
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(width * 0.04f, 0f)) }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/05c_reader_scrub.png")
        // 40 % of the track from page 4: about page 13 (the finger's place, not a step or two).
        val shown = compose.onAllNodes(androidx.compose.ui.test.hasText(" / 24", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() } }
            .mapNotNull { Regex("""^(\d+) / 24$""").find(it.text)?.groupValues?.get(1)?.toInt() }
        assertTrue("the slider follows the finger: $shown", shown.any { it in 10..16 })
        slider.performTouchInput { up() }
        compose.waitForIdle()
        // Let go: the reader is on that page (read from the reader: the controls may have hidden).
        val page = ReaderScreen::class.java.getDeclaredField("page\$delegate").let {
            it.isAccessible = true
            (it.get(reader) as androidx.compose.runtime.MutableIntState).intValue
        }
        assertTrue("the reader went to the page: ${page + 1}", page + 1 in 10..16)
    }

    @Test fun readerRetry() {
        // D004: a page that failed loads again on Retry (a new request reaches the loader), twice in a row.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val page = "https://i.nhentai.net/galleries/9/4.jpg"
        var asked = 0
        var offline = true
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                if (chain.request.data.toString() == page) {
                    asked++
                    if (offline) return@Interceptor coil3.request.ErrorResult(null, chain.request, java.io.IOException("offline"))
                }
                FakeImages.intercept(chain)
            })
        }.build())
        val tabs = Tabs()
        val source = ReaderSource(
            500001, "Sample Gallery", (0 until 24).map { com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
            "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
        )
        tabs.home.push(ReaderScreen(source, 3))
        shoot("54_reader_failed", tabs, settle = 500)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
        val first = asked
        compose.onNodeWithText("Reload Page").performClick()
        compose.waitUntil(5_000) { asked > first && compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isNotEmpty() }
        val second = asked
        offline = false
        compose.onNodeWithText("Reload Page").performClick()
        compose.waitUntil(5_000) { asked > second && compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/55_reader_retried.png")
    }

    @Test fun readerFade() {
        // Tap to Turn › Page Fade: a tap on the edge fades the next page in where it is.
        AppSettings.tapTurn = com.atlanyuey.aenht.core.TapTurn.Fade
        try {
            val tabs = Tabs()
            val source = ReaderSource(
                500001, "Sample Gallery", (0 until 24).map { com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
                "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
            )
            tabs.home.push(ReaderScreen(source, 3))
            shoot("35_reader_fade_before", tabs, settle = 500)
            compose.mainClock.autoAdvance = false
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.92f, centerY)) }
            // A second tap during the fade ends it and turns on from the page it was showing.
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.92f, centerY)) }
            compose.mainClock.advanceTimeBy(120)
            compose.onRoot().captureRoboImage("build/screens/36_reader_fade_mid.png")
            compose.mainClock.advanceTimeBy(600)
            compose.onRoot().captureRoboImage("build/screens/37_reader_fade_after.png")
        } finally {
            AppSettings.tapTurn = com.atlanyuey.aenht.core.TapTurn.Slide
        }
    }

    @Test fun readerSpreadFade() {
        // Tap to Turn › Spread Fade: the next page shows in a circle growing from the tap.
        AppSettings.tapTurn = com.atlanyuey.aenht.core.TapTurn.Spread
        try {
            val tabs = Tabs()
            tabs.home.push(ReaderScreen(sampleSource(), 3))
            shoot("70_reader_spread_before", tabs, settle = 500)
            compose.mainClock.autoAdvance = false
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.9f, height * 0.3f)) }
            compose.mainClock.advanceTimeBy(160)
            compose.onRoot().captureRoboImage("build/screens/71_reader_spread_mid.png")
            compose.mainClock.advanceTimeBy(600)
            compose.onRoot().captureRoboImage("build/screens/72_reader_spread_after.png")
        } finally {
            AppSettings.tapTurn = com.atlanyuey.aenht.core.TapTurn.Slide
        }
    }

    private fun sampleSource() = ReaderSource(
        500001, "Sample Gallery", (0 until 24).map { com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
        "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
    )

    /** Allow Smaller Pages: pinched in, the page is smaller than the screen, and Reset Size shows. */
    @Test fun readerSmaller() {
        AppSettings.allowSmallerPages = true
        try {
            val tabs = Tabs()
            tabs.home.push(ReaderScreen(sampleSource(), 3))
            shoot("73_reader_before_pinch", tabs, settle = 500)
            compose.onRoot().performTouchInput {
                pinch(
                    androidx.compose.ui.geometry.Offset(centerX - 300f, centerY), androidx.compose.ui.geometry.Offset(centerX - 120f, centerY),
                    androidx.compose.ui.geometry.Offset(centerX + 300f, centerY), androidx.compose.ui.geometry.Offset(centerX + 120f, centerY),
                    durationMillis = 400,
                )
            }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            // The controls back, with Reset Size.
            compose.onRoot().performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screens/74_reader_smaller.png")
        } finally {
            AppSettings.allowSmallerPages = false
        }
    }

    /**
     * By default, three bubbles in a row: Reader Settings on the left of the
     * bar, Auto Read on its right, each in its own bubble (not in the bar).
     */
    @Config(qualifiers = "sw834dp-w1194dp-h834dp-xhdpi")
    @Test fun tabletReaderBar() {
        val tabs = Tabs()
        tabs.home.push(ReaderScreen(sampleSource(), 3))
        shoot("75a_tablet_reader_bar", tabs, settle = 500)
        assertThreeBubbles()
    }

    /** A phone by default (Automatic): the buttons float above the bar, the slider has the whole bar. */
    @Test fun phoneReaderButtonsAbove() {
        val tabs = Tabs()
        tabs.home.push(ReaderScreen(sampleSource(), 3))
        shoot("75b_phone_reader_above", tabs, settle = 500)
        assertTrue("buttons above the bar", !buttonsLevelWithSlider())
    }

    /**
     * Beside the Bar chosen on a phone: three bubbles, and the pages left
     * give way so the slider still has room to drag.
     */
    @Test fun phoneReaderButtonsBeside() {
        AppSettings.readerButtonPlacement = com.atlanyuey.aenht.core.ReaderButtonPlacement.Beside
        try {
            val tabs = Tabs()
            tabs.home.push(ReaderScreen(sampleSource(), 3))
            shoot("75c_phone_reader_beside", tabs, settle = 500)
            assertThreeBubbles()
            val slider = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNode().boundsInRoot
            val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
            assertTrue("the slider keeps its room: ${slider.width / density} dp", slider.width / density >= 150f)
            assertTrue("no pages left on a narrow bar", compose.onAllNodes(androidx.compose.ui.test.hasText("Pages left", substring = true)).fetchSemanticsNodes().isEmpty())
        } finally {
            AppSettings.readerButtonPlacement = com.atlanyuey.aenht.core.ReaderButtonPlacement.Auto
        }
    }

    /**
     * Keep Auto Read Button on Screen: with the controls away it's in the
     * bottom right corner (not where it was beside the bar), and it works.
     */
    @Config(qualifiers = "sw834dp-w1194dp-h834dp-xhdpi")
    @Test fun autoReadKeptOnScreen() {
        AppSettings.autoReadPinned = true
        try {
            val tabs = Tabs()
            tabs.home.push(ReaderScreen(sampleSource(), 3))
            shoot("75d_reader_pinned_shown", tabs, settle = 800)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
            val shown = autoReadBounds()
            assertTrue("with the controls, beside the bar: $shown", buttonsLevelWithSlider() && shown.right < root.right - 100 * density)
            // Left alone, the controls hide; the button goes to the corner.
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screens/75e_reader_pinned_hidden.png")
            assertTrue("the slider is away",
                compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty())
            val hidden = autoReadBounds()
            assertTrue("bottom right corner: $hidden in $root",
                hidden.right > root.right - 40 * density && hidden.bottom > root.bottom - 60 * density)
            // And it works: a tap starts Auto Read.
            compose.onNode(androidx.compose.ui.test.hasContentDescription("Auto Read"), useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNode(androidx.compose.ui.test.hasContentDescription("Stop Auto Read"), useUnmergedTree = true).assertExists()
        } finally {
            AppSettings.autoReadPinned = false
        }
    }

    private fun autoReadBounds() =
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Auto Read"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Settings left of the bar, Auto Read right of it, both level with it and outside it. */
    private fun assertThreeBubbles() {
        val slider = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().boundsInRoot
        val settings = compose.onNode(androidx.compose.ui.test.hasContentDescription("Reader Settings"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val auto = autoReadBounds()
        // The bar's page number (the title has one too, at the top).
        val page = compose.onAllNodes(androidx.compose.ui.test.hasText(" / 24", substring = true)).fetchSemanticsNodes()
            .map { it.boundsInRoot }.maxBy { it.top }
        assertTrue("level with the bar", buttonsLevelWithSlider())
        assertTrue("settings on the left, outside the bar: $settings, page number $page", settings.right < page.left - 20)
        assertTrue("auto read on the right, outside the bar: $auto, slider $slider", auto.left > slider.right + 60)
    }

    private fun buttonsLevelWithSlider(): Boolean {
        val slider = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode().boundsInRoot
        return listOf("Reader Settings", "Auto Read").all { label ->
            val button = compose.onNode(androidx.compose.ui.test.hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            button.center.y in slider.top - 4f..slider.bottom + 4f
        }
    }

    /** Continuous on a tablet held sideways: by default a whole page shows, in the middle. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletContinuous() {
        AppSettings.readMode = ReadMode.Vertical
        AppSettings.continuousPageScale = 1.0
        try {
            val tabs = Tabs()
            tabs.home.push(ReaderScreen(sampleSource(), 3))
            shoot("75_tablet_continuous", tabs, settle = 800)
        } finally {
            AppSettings.readMode = ReadMode.LeftToRight
        }
    }

    @Test fun readerSwipeFade() {
        // Swipe Fade: the page fades away under the finger, the way it goes; the pages don't move.
        AppSettings.swipeTurn = com.atlanyuey.aenht.core.SwipeTurn.Sweep
        try {
            val tabs = Tabs()
            val source = ReaderSource(
                500001, "Sample Gallery", (0 until 24).map { com.atlanyuey.aenht.screens.ReaderPage(it, "https://i.nhentai.net/galleries/9/${it + 1}.jpg", 0.7f) },
                "https://nhentai.net/g/500001/", com.atlanyuey.aenht.shared.site.GallerySummary.from(com.atlanyuey.aenht.core.GalleryItem(500001, numPages = 24)),
            )
            tabs.home.push(ReaderScreen(source, 3))
            shoot("46_reader_swipe_before", tabs, settle = 500)
            compose.mainClock.autoAdvance = false
            // A slow swipe to the left, a little at a time: the page doesn't move while the finger does.
            compose.onRoot().performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * 0.8f, centerY))
                repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(-width * 0.05f, 0f)); advanceEventTime(120) }
            }
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().captureRoboImage("build/screens/47_reader_swipe_held.png")
            compose.onRoot().performTouchInput { up() }
            compose.mainClock.advanceTimeBy(150)
            compose.onRoot().captureRoboImage("build/screens/48_reader_swipe_mid.png")
            compose.mainClock.advanceTimeBy(600)
            compose.onRoot().captureRoboImage("build/screens/49_reader_swipe_after.png")
        } finally {
            AppSettings.swipeTurn = com.atlanyuey.aenht.core.SwipeTurn.Slide
        }
    }

    @Test fun homeScrolled() {
        AppSettings.hideBarsOnScroll = true
        shoot("24_home_docked")
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 200f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        // The sort, language and search buttons docked in the bar; the tab bar slid away.
        compose.onRoot().captureRoboImage("build/screens/24_home_docked.png")
    }

    @Test fun aboutEgg() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.AboutScreen())
        shoot("26_about", tabs, tab = 3)
        // Step the clock by hand to catch the animation half way.
        compose.mainClock.autoAdvance = false
        val icon = compose.onNodeWithContentDescription("AENHT")
        icon.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        icon.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(350)
        compose.onRoot().captureRoboImage("build/screens/27_about_egg.png")
        compose.mainClock.autoAdvance = true
    }

    @Test fun more() = shoot("06_more", tab = 3)

    @Test fun search() {
        shoot("07_search")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/07_search.png")
    }

    @Test fun history() = shoot("08_history", tab = 2)

    private fun sample(i: Int) = com.atlanyuey.aenht.core.GalleryItem(
        id = 500000 + i, mediaId = "${100 + i}", englishTitle = listOf("Summer Vacation Story", "Night Walk", "Rainy Day Café", "The Library at Midnight", "Festival", "Letters")[i % 6],
        thumbnail = "galleries/${100 + i}/thumb.jpg", thumbnailWidth = 350, thumbnailHeight = listOf(500, 470, 540, 495, 520, 480)[i % 6],
        numPages = 20 + i, tagIds = listOf(listOf(6346, 29963, 12227)[i % 3], 17249),
    )

    private fun seedHistory() {
        val store = com.atlanyuey.aenht.core.HistoryStore
        store.clear()
        (5 downTo 0).forEach { i -> store.record(sample(i), sample(i).englishTitle!!, 20 + i, page = if (i % 2 == 0) i * 3 else null) }
    }

    @Test fun historyCards() {
        seedHistory()
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.Cards
        shoot("28_history_cards", tab = 2)
        com.atlanyuey.aenht.core.HistoryStore.clear()
    }

    @Test fun historyList() {
        seedHistory()
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.List
        shoot("29_history_list", tab = 2)
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.Cards
        com.atlanyuey.aenht.core.HistoryStore.clear()
    }

    private fun seedDownloads(): Tabs {
        val downloads = com.atlanyuey.aenht.core.Downloads
        downloads.records.clear()
        val states = listOf("finished", "downloading", "paused", "finished", "failed")
        states.forEachIndexed { i, state ->
            val item = sample(i)
            val pages = (1..item.numPages).map { com.atlanyuey.aenht.core.DownloadPage(it, "https://i.nhentai.net/galleries/${100 + i}/$it.jpg", "%04d.jpg".format(it), 1280, 1800) }
            downloads.records.add(
                com.atlanyuey.aenht.core.DownloadRecord(
                    id = item.id, mediaId = item.mediaId, title = item.englishTitle!!, thumbnail = item.thumbnail,
                    thumbWidth = item.thumbnailWidth, thumbHeight = item.thumbnailHeight, pages = pages,
                    completed = if (state == "finished") pages.size else pages.size * (i + 1) / 6,
                    state = state, added = 0, tagIds = item.tagIds,
                ),
            )
        }
        return Tabs().also { it.more.push(com.atlanyuey.aenht.screens.DownloadsScreen()) }
    }

    @Test fun downloadsCards() {
        val tabs = seedDownloads()
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.Cards
        shoot("30_downloads_cards", tabs, tab = 3)
        // A long press shows Pause / Resume and Delete above Share.
        compose.onNodeWithText("Night Walk").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/31_downloads_menu.png")
        com.atlanyuey.aenht.core.Downloads.records.clear()
    }

    @Test fun downloadsList() {
        val tabs = seedDownloads()
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.List
        shoot("32_downloads_list", tabs, tab = 3)
        AppSettings.libraryStyle = com.atlanyuey.aenht.core.LibraryStyle.Cards
        com.atlanyuey.aenht.core.Downloads.records.clear()
    }

    @Test fun searchNumber() {
        val tabs = Tabs()
        tabs.home.push(com.atlanyuey.aenht.screens.SearchResultsScreen("500001", null))
        shoot("20_search_number", tabs)
    }

    @Test fun searchHistory() {
        AppSettings.clearRecentSearches()
        listOf("glasses", "artist:sample", "500001", "beach").forEach { AppSettings.addRecentSearch(com.atlanyuey.aenht.shared.site.Site.NHENTAI, it) }
        shoot("21_search_history")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/21_search_history.png")
        compose.onNodeWithText("artist:sample").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/22_search_history_delete.png")
        AppSettings.clearRecentSearches()
    }

    @Test fun cardSize() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.CardSizeScreen())
        shoot("33_card_size", tabs, tab = 3)
    }

    @Test fun appearance() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.AppearanceScreen())
        shoot("09_appearance", tabs, tab = 3)
    }

    @Test fun menu() {
        val overlays = com.atlanyuey.aenht.ui.components.OverlayController()
        val density = 3f
        overlays.menu(
            androidx.compose.ui.geometry.Rect(333f * density, 6f * density, 377f * density, 50f * density),
            listOf(
                com.atlanyuey.aenht.core.SearchSort.entries.map {
                    com.atlanyuey.aenht.ui.components.MenuItem(it.title, checked = it == com.atlanyuey.aenht.core.SearchSort.Recent) {}
                },
            ),
            title = "Sort",
        )
        shoot("11_menu", overlays = overlays)
    }

    @Test fun alert() {
        val overlays = com.atlanyuey.aenht.ui.components.OverlayController()
        overlays.alert(
            "Open Gallery?", "The clipboard contains a link to gallery #500001.",
            listOf(
                com.atlanyuey.aenht.ui.components.DialogButton("Cancel", com.atlanyuey.aenht.ui.components.ButtonRole.Cancel),
                com.atlanyuey.aenht.ui.components.DialogButton("Open", com.atlanyuey.aenht.ui.components.ButtonRole.Preferred),
            ),
        )
        shoot("12_alert", overlays = overlays)
    }

    @Test fun galleryExpanded() {
        val tabs = Tabs()
        tabs.home.push(GalleryScreen(500001, null))
        shoot("15_gallery_tags", tabs)
        compose.onNodeWithText("A Very Long", substring = true).performClick()
        compose.onAllNodesWithContentDescription("See All")[0].performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/16_gallery_expanded.png")
        compose.onNodeWithText("Parodies").performScrollTo()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/17_gallery_tags_open.png")
    }

    @Config(qualifiers = "+zh-rCN")
    @Test fun galleryChinese() {
        AppSettings.theme = ThemeMode.Dark
        val tabs = Tabs()
        tabs.home.push(GalleryScreen(500001, null))
        shoot("13_gallery_zh_dark", tabs)
    }

    @Config(qualifiers = "w834dp-h1194dp-xhdpi")
    @Test fun tabletSearch() {
        AppSettings.tabBarPosition = com.atlanyuey.aenht.core.TabBarPosition.Leading
        shoot("18_tablet_left")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/19_tablet_search.png")
        AppSettings.tabBarPosition = com.atlanyuey.aenht.core.TabBarPosition.Center
    }

    @Config(qualifiers = "w834dp-h1194dp-xhdpi")
    @Test fun tablet() {
        AppSettings.tabBarPosition = com.atlanyuey.aenht.core.TabBarPosition.Trailing
        shoot("14_tablet_right")
        AppSettings.tabBarPosition = com.atlanyuey.aenht.core.TabBarPosition.Center
    }

    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscape() = shoot("34_tablet_landscape")

    /** A tablet held sideways: the gallery's details on the left, its pages on the right. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeGallery() {
        val tabs = Tabs()
        tabs.home.push(GalleryScreen(500001, null))
        shoot("52_tablet_landscape_gallery", tabs, settle = 2500)
    }

    /** Tablet Layout › Right Half: Other Versions, Similar and Comments. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeGalleryRelated() {
        AppSettings.tabletDetailContent = com.atlanyuey.aenht.core.TabletDetailContent.Related
        try {
            val tabs = Tabs()
            tabs.home.push(GalleryScreen(500001, null))
            shoot("76_tablet_gallery_related", tabs, settle = 2500)
        } finally {
            AppSettings.tabletDetailContent = com.atlanyuey.aenht.core.TabletDetailContent.Pages
        }
    }

    /** Tablet Layout › Pages Width: Three Columns. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeGalleryNarrow() {
        AppSettings.tabletNarrowPages = true
        try {
            val tabs = Tabs()
            tabs.home.push(com.atlanyuey.aenht.screens.EhGalleryScreen(4321, "1a2b3c4d5e", null))
            shoot("77_tablet_eh_gallery_narrow", tabs, settle = 2500)
        } finally {
            AppSettings.tabletNarrowPages = false
        }
    }

    /** Me on a tablet held sideways: the list and the page side by side; then Tablet Layout. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeMe() {
        shoot("78_tablet_me", tab = 3)
        compose.onNodeWithText("Tablet Layout").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/79_tablet_layout_settings.png")
    }

    /** Tablet Layout › Home off: the pinned rows one above the other. */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeHomeOff() {
        AppSettings.tabletHome = false
        try { shoot("80_tablet_home_off") } finally { AppSettings.tabletHome = true }
    }

    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun tabletLandscapeEhGallery() {
        val tabs = Tabs()
        tabs.home.push(com.atlanyuey.aenht.screens.EhGalleryScreen(4321, "1a2b3c4d5e", null))
        shoot("53_tablet_landscape_eh_gallery", tabs, settle = 2500)
    }

    /**
     * Turning the tablet keeps the list where it was (not back at the top).
     * As in the app, the activity stays and the configuration changes: here
     * the window is the landscape one, with the app first in a portrait part of it.
     */
    @Config(qualifiers = "w1194dp-h834dp-xhdpi")
    @Test fun rotationKeepsThePlace() {
        val portrait = mutableStateOf(true)
        val linkedGallery = mutableStateOf<com.atlanyuey.aenht.shared.site.GalleryLink?>(null)
        val tabs = Tabs()
        val overlays = com.atlanyuey.aenht.ui.components.OverlayController()
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalConfiguration.current
            val configuration = android.content.res.Configuration(base).apply {
                if (portrait.value) { screenWidthDp = 600; screenHeightDp = 834 }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration) {
                androidx.compose.foundation.layout.Box(
                    if (portrait.value) androidx.compose.ui.Modifier.requiredWidth(600.dp) else androidx.compose.ui.Modifier,
                ) {
                    AppTheme { RootView(linkedGallery, tabs, 0, overlays) }
                }
            }
        }
        // The galleries are there before scrolling.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Night Walk").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.onNodeWithText("All Languages").assertIsDisplayed()
        repeat(3) {
            compose.onRoot().performTouchInput { swipeUp(startY = centerY + 300f, endY = centerY - 300f) }
            compose.mainClock.advanceTimeBy(800)
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/50_rotate_scrolled.png")
        // Scrolled: the top of the list is gone (the large header with the language under the feed's name docks in the bar).
        compose.onAllNodesWithText("All Languages").assertCountEquals(0)
        portrait.value = false
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/51_rotate_landscape.png")
        // Still down the list after rotating: back at the top would show the header again.
        compose.onAllNodesWithText("All Languages").assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("Night Walk", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Snow Mountain").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("Garden", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun reading() {
        val tabs = Tabs()
        tabs.more.push(com.atlanyuey.aenht.screens.ReadingScreen())
        shoot("10_reading", tabs, tab = 3)
    }
}
