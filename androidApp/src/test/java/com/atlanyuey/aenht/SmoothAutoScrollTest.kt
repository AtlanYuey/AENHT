package com.atlanyuey.aenht

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.screens.*
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual reader, Coil and Compose frame clock; a synthetic strip is twenty times its width. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SmoothAutoScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun open(mode: ReadMode, gate: CompletableDeferred<Unit>? = null, offline: AtomicBoolean? = null,
                     continueOnDrag: Boolean = false, pageCount: Int = 1, shortPages: Boolean = false): ReaderScreen {
        ScreenshotTest().setUp()
        AppSettings.readMode = mode
        AppSettings.autoScrollSecondsPerScreen = 5.0
        AppSettings.continueAutoScrollOnDrag = continueOnDrag
        AppSettings.superResolution = SrMode.Off
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureImage = Bitmap.createBitmap(160, if (shortPages) 100 else 3200, Bitmap.Config.ARGB_8888).asImage()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                gate?.await()
                if (offline?.get() == true) return@Interceptor ErrorResult(null, chain.request, IOException("synthetic offline image"))
                SuccessResult(fixtureImage, chain.request, DataSource.NETWORK)
            })
        }.build())
        val reader = ReaderScreen(ReaderSource(
            980006, "Synthetic long strip", (0 until pageCount).map { ReaderPage(it, if (shortPages) fixtureImage else "https://fixture.invalid/strip-$it.png", if (shortPages) 1.6f else .7f) },
            "https://nhentai.net/g/980006/", GallerySummary.from(GalleryItem(980006, numPages = pageCount)),
        ), 0)
        val tabs = Tabs().also { it.home.push(reader) }
        val linked = mutableStateOf<GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linked, tabs, 0) } }
        compose.waitForIdle()
        return reader
    }

    private fun list(reader: ReaderScreen) = ReaderScreen::class.java.getDeclaredField("list").let {
        it.isAccessible = true
        it.get(reader) as LazyListState
    }

    private fun advance(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun reset() {
        AppSettings.readMode = ReadMode.LeftToRight
        AppSettings.autoScrollSecondsPerScreen = 10.0
        AppSettings.continueAutoScrollOnDrag = false
    }

    @Test fun lastLongImageKeepsMovingAndManualDragPausesWithoutSnappingBack() {
        try {
            val reader = open(ReadMode.Vertical)
            val list = list(reader)
            compose.waitUntil(10_000) { list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { it > list.layoutInfo.viewportSize.height * 5 } == true }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(1200)
            assertEquals(0, list.firstVisibleItemIndex)
            assertTrue("The final image must scroll while its bottom is still below the screen", list.firstVisibleItemScrollOffset > 100)
            val beforeDrag = list.firstVisibleItemScrollOffset
            compose.onRoot().performTouchInput { swipeUp(durationMillis = 600) }
            advance(2000)
            val paused = list.firstVisibleItemScrollOffset
            advance(1500)
            assertEquals("Manual dragging must leave automatic scrolling paused", paused, list.firstVisibleItemScrollOffset)
            assertTrue("Recording the same page must not return to the top", paused > beforeDrag)
        } finally { reset() }
    }

    @Test fun enabledOptionResumesAfterManualDragWithoutNeedingPlayAgain() {
        try {
            val reader = open(ReadMode.Vertical, continueOnDrag = true)
            val list = list(reader)
            compose.waitUntil(10_000) { list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { it > list.layoutInfo.viewportSize.height * 5 } == true }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(800)
            val before = list.firstVisibleItemScrollOffset
            compose.onRoot().performTouchInput { swipeUp(durationMillis = 600) }
            advance(2500)
            val resumed = list.firstVisibleItemScrollOffset
            advance(1000)
            assertTrue(resumed > before)
            assertTrue("Automatic scrolling should resume at the newly selected position", list.firstVisibleItemScrollOffset > resumed)
            compose.onRoot().performTouchInput { click(center) }
            compose.waitUntil(3000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithContentDescription("Pause Auto Scroll").fetchSemanticsNodes().isNotEmpty()
            }
            advance(300)
            compose.onNodeWithContentDescription("Pause Auto Scroll").assertIsDisplayed()
        } finally { reset() }
    }

    @Test fun controlsStayVisibleAcrossFastPageBoundariesUntilTheNormalTimeout() {
        val previousDelay = AppSettings.readerControlsDelay
        try {
            val reader = open(ReadMode.Vertical, pageCount = 30, shortPages = true)
            val list = list(reader)
            AppSettings.autoScrollSecondsPerScreen = 1.0
            AppSettings.readerControlsDelay = 3.0
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(300)
            compose.onRoot().performTouchInput { click(center) }
            compose.waitUntil(3000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithContentDescription("Pause Auto Scroll").fetchSemanticsNodes().isNotEmpty()
            }
            advance(300)
            val first = list.firstVisibleItemIndex
            advance(1200)
            assertTrue("This must cross image boundaries while the controls are visible", list.firstVisibleItemIndex > first)
            compose.onNodeWithContentDescription("Pause Auto Scroll").assertIsDisplayed()
            compose.onNodeWithContentDescription("Options").assertIsDisplayed()
            advance(2300)
            compose.onNodeWithContentDescription("Pause Auto Scroll").assertDoesNotExist()
            compose.onNodeWithContentDescription("Options").assertDoesNotExist()
        } finally { AppSettings.readerControlsDelay = previousDelay; reset() }
    }

    @Test fun continuousWithGapsStopsOnlyAtTheBottomOfTheLastStrip() {
        try {
            val reader = open(ReadMode.VerticalGap)
            val list = list(reader)
            compose.waitUntil(10_000) { list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { it > list.layoutInfo.viewportSize.height * 5 } == true }
            compose.mainClock.autoAdvance = false
            val uiScope = ReaderScreen::class.java.getDeclaredField("uiScope").let { it.isAccessible = true; it.get(reader) as CoroutineScope }
            compose.runOnIdle {
                uiScope.launch {
                    val layout = list.layoutInfo
                    list.scrollToItem(0, layout.visibleItemsInfo.first().size - layout.viewportSize.height - 20)
                }
            }
            advance(100)
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(700)
            assertFalse("The actual content bottom should end playback", list.canScrollForward)
            compose.onRoot().performTouchInput { click(center) }
            // Reader taps also use the screen's navigation scope; wait for that callback and its UI frame.
            compose.waitUntil(3000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithContentDescription("Auto Scroll").fetchSemanticsNodes().isNotEmpty()
            }
            advance(600)
            compose.onRoot().captureRoboImage("build/screens/auto-scroll-end.png")
            compose.onNodeWithContentDescription("Auto Scroll").assertIsDisplayed()
            val end = list.firstVisibleItemScrollOffset
            advance(1000)
            assertEquals(end, list.firstVisibleItemScrollOffset)
        } finally { reset() }
    }

    @Test fun loadingWaitsThenContinuesAfterTheRealImageHeightIsKnown() {
        val gate = CompletableDeferred<Unit>()
        try {
            val reader = open(ReadMode.Vertical, gate)
            val list = list(reader)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(1200)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            gate.complete(Unit)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10_000) { list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { it > list.layoutInfo.viewportSize.height * 5 } == true }
            compose.mainClock.autoAdvance = false
            advance(1200)
            assertTrue("Loading must resume with the tall image instead of ending at the placeholder's bottom", list.firstVisibleItemScrollOffset > 100)
        } finally { gate.complete(Unit); reset() }
    }

    @Test fun failurePausesAndRetryKeepsThePositionUntilExplicitResume() {
        val offline = AtomicBoolean(true)
        try {
            val reader = open(ReadMode.Vertical, offline = offline)
            val list = list(reader)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(300)
            compose.onNodeWithText("Auto Scroll Paused").assertIsDisplayed()
            assertEquals(0, list.firstVisibleItemScrollOffset)
            offline.set(false)
            compose.onAllNodesWithText("Retry").onLast().performClick()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(32)
                list.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.let { it > list.layoutInfo.viewportSize.height * 5 } == true
            }
            advance(1200)
            compose.onNodeWithText("Auto Scroll Paused").assertDoesNotExist()
            assertEquals("Retry should not resume playback on the user's behalf", 0, list.firstVisibleItemScrollOffset)
            compose.onRoot().performTouchInput { click(center) }
            compose.waitUntil(3000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithContentDescription("Auto Scroll").fetchSemanticsNodes().isNotEmpty()
            }
            advance(600)
            compose.onNodeWithContentDescription("Auto Scroll").performClick()
            advance(1200)
            assertTrue(list.firstVisibleItemScrollOffset > 100)
        } finally { reset() }
    }
}
