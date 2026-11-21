package com.atlanyuey.aenht

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.screens.*
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's page size and page turns on a tablet held sideways (the
 * same rules as the iPad's): Swipe Fade follows the finger and can be
 * taken back, an enlarged page turns nothing and holds back, a page made
 * smaller makes the next ones as small, the continuous strip's width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1194dp-h834dp-xhdpi")
class ReaderGesturesTest {
    @get:Rule val compose = createComposeRule()

    private fun open(mode: ReadMode, start: Int = 2): ReaderScreen {
        ScreenshotTest().setUp()
        AppSettings.readMode = mode
        AppSettings.superResolution = SrMode.Off
        savedDoublePage = AppSettings.doublePage
        AppSettings.doublePage = DoublePage.Off
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = Bitmap.createBitmap(700, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.asImage()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(Interceptor { chain -> SuccessResult(image, chain.request, DataSource.NETWORK) })
        }.build())
        val reader = ReaderScreen(ReaderSource(
            980007, "Gestures", (0 until 8).map { ReaderPage(it, "https://fixture.invalid/page-$it.png", .7f) },
            "https://nhentai.net/g/980007/", GallerySummary.from(GalleryItem(980007, numPages = 8)),
        ), start)
        val tabs = Tabs().also { it.home.push(reader) }
        val linked = mutableStateOf<GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linked, tabs, 0) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        return reader
    }

    private var savedDoublePage = AppSettings.doublePage

    @After fun reset() {
        AppSettings.doublePage = savedDoublePage
        AppSettings.readMode = ReadMode.LeftToRight
        AppSettings.swipeTurn = SwipeTurn.Slide
        AppSettings.tapTurn = TapTurn.Slide
        AppSettings.allowSmallerPages = false
        AppSettings.keepContinuousPageSize = true
        AppSettings.continuousPageScale = 1.0
        AppSettings.doubleTapTopToStart = true
    }

    private fun field(reader: ReaderScreen, name: String): Any =
        ReaderScreen::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(reader)!! }

    private fun page(reader: ReaderScreen) = (field(reader, "page\$delegate") as MutableIntState).intValue
    private fun pageScale(reader: ReaderScreen) = (field(reader, "pageScale\$delegate") as MutableFloatState).floatValue
    private fun stripScale(reader: ReaderScreen) = (field(reader, "stripScale\$delegate") as MutableFloatState).floatValue

    private fun settle(millis: Long = 1000) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun pinchOut() = compose.onRoot().performTouchInput {
        pinch(Offset(centerX - 60f, centerY), Offset(centerX - 400f, centerY),
              Offset(centerX + 60f, centerY), Offset(centerX + 400f, centerY), durationMillis = 400)
    }

    private fun pinchIn() = compose.onRoot().performTouchInput {
        pinch(Offset(centerX - 500f, centerY), Offset(centerX - 200f, centerY),
              Offset(centerX + 500f, centerY), Offset(centerX + 200f, centerY), durationMillis = 400)
    }

    @Test fun swipeFadeFollowsTheFingerAndCanBeTakenBack() {
        AppSettings.swipeTurn = SwipeTurn.Sweep
        val reader = open(ReadMode.LeftToRight)
        // Held part way: the next page is in place under the old one.
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.8f, centerY))
            repeat(6) { moveBy(Offset(-width * 0.04f, 0f)); advanceEventTime(60) }
        }
        settle(100)
        assertEquals(3, page(reader))
        // Brought back and let go slowly: back on the page it started from.
        compose.onRoot().performTouchInput {
            repeat(6) { moveBy(Offset(width * 0.04f, 0f)); advanceEventTime(120) }
            up()
        }
        settle()
        assertEquals(2, page(reader))
        // A full swipe turns it.
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f, durationMillis = 250) }
        settle()
        assertEquals(3, page(reader))
        // Right, back.
        compose.onRoot().performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.8f, durationMillis = 250) }
        settle()
        assertEquals(2, page(reader))
    }

    @Test fun spreadAndPageFadeTapsTurn() {
        AppSettings.tapTurn = TapTurn.Spread
        val reader = open(ReadMode.LeftToRight)
        compose.onRoot().performTouchInput { click(Offset(width * 0.92f, centerY)) }
        settle()
        assertEquals(3, page(reader))
        AppSettings.tapTurn = TapTurn.Fade
        compose.onRoot().performTouchInput { click(Offset(width * 0.08f, centerY)) }
        settle()
        assertEquals(2, page(reader))
    }

    @Test fun anEnlargedPageTurnsNothingAndBackResetsIt() {
        val reader = open(ReadMode.LeftToRight)
        pinchOut()
        settle()
        assertTrue("pinched out, the page is enlarged (${pageScale(reader)})", pageScale(reader) > 1.01f)
        assertTrue(reader.holdsBack)
        compose.onRoot().performTouchInput { click(Offset(width * 0.92f, centerY)) }
        settle()
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.1f, durationMillis = 200) }
        settle()
        assertEquals("enlarged, neither a tap nor a swipe turns", 2, page(reader))
        // Back (held): the page goes back to its size, the reader stays.
        reader.onBackHeld()
        settle()
        assertEquals(1f, pageScale(reader), 0.02f)
        assertFalse(reader.holdsBack)
        compose.onRoot().performTouchInput { click(Offset(width * 0.92f, centerY)) }
        settle()
        assertEquals(3, page(reader))
    }

    @Test fun smallerPagesOnlyWhenAllowedAndTheNextComeAsSmall() {
        val reader = open(ReadMode.LeftToRight)
        pinchIn()
        settle()
        assertEquals("not allowed, a page never gets smaller", 1f, pageScale(reader), 0.02f)
        AppSettings.allowSmallerPages = true
        settle(100)
        pinchIn()
        settle()
        val smaller = pageScale(reader)
        assertTrue("allowed, pinching in makes it smaller ($smaller)", smaller < 0.99f)
        assertFalse("smaller isn't enlarged: back still leaves", reader.holdsBack)
        compose.onRoot().performTouchInput { click(Offset(width * 0.95f, centerY)) }
        settle(1500)
        assertEquals(3, page(reader))
        assertEquals("the next page comes as small", smaller, pageScale(reader), 0.03f)
        // The controls (hidden by the turn; a tap's timer runs on the real clock here) show Reset Size; it brings the size back.
        @Suppress("UNCHECKED_CAST")
        (field(reader, "chromeVisible\$delegate") as androidx.compose.runtime.MutableState<Boolean>).value = true
        settle(300)
        compose.onNodeWithContentDescription("Reset Size", useUnmergedTree = true).assertExists().performClick()
        settle()
        assertEquals(1f, pageScale(reader), 0.02f)
        compose.onRoot().performTouchInput { click(Offset(width * 0.95f, centerY)) }
        settle(1500)
        assertEquals(4, page(reader))
        assertEquals("after Reset Size the next page is at its size again", 1f, pageScale(reader), 0.02f)
    }

    @Test fun doubleTapEnlargesAndBringsBackTheFittedSize() {
        AppSettings.allowSmallerPages = true
        val reader = open(ReadMode.LeftToRight)
        compose.onRoot().performTouchInput { doubleClick(center) }
        settle(1500)
        assertTrue("double tap enlarges (${pageScale(reader)})", pageScale(reader) > 2f)
        compose.onRoot().performTouchInput { doubleClick(center) }
        settle(1500)
        // Fitted, not the smallest size Allow Smaller Pages allows.
        assertEquals(1f, pageScale(reader), 0.02f)
    }

    @Test fun doubleTapOnTheTitleGoesToTheFirstPageUnlessTurnedOff() {
        val reader = open(ReadMode.Vertical, start = 5)
        compose.waitUntil(5_000) { page(reader) == 5 }
        // A single tap on the title does nothing.
        compose.onNodeWithText("Gestures").performTouchInput { click() }
        settle()
        assertEquals(5, page(reader))
        compose.onNodeWithText("Gestures").performTouchInput { doubleClick() }
        settle()
        assertEquals(0, page(reader))
        // Turned off: nothing.
        AppSettings.doubleTapTopToStart = false
        AppSettings.readMode = ReadMode.LeftToRight
        settle()
        compose.onRoot().performTouchInput { click(Offset(width * 0.95f, centerY)) }
        settle()
        assertEquals(1, page(reader))
        @Suppress("UNCHECKED_CAST")
        (field(reader, "chromeVisible\$delegate") as androidx.compose.runtime.MutableState<Boolean>).value = true
        settle(300)
        compose.onNodeWithText("Gestures").performTouchInput { doubleClick() }
        settle()
        assertEquals(1, page(reader))
    }

    @Test fun continuousStripWidthPinchesAndIsKept() {
        AppSettings.continuousPageScale = 1.0
        val reader = open(ReadMode.Vertical)
        assertEquals(1f, stripScale(reader), 0.001f)
        // By default a whole page shows: narrower than the screen held sideways.
        val width = field(reader, "stripWidth") as Float
        val screen = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("default width $width under $screen", width < screen * 0.8f)
        pinchOut()
        settle()
        val wider = stripScale(reader)
        assertTrue("pinched out, the pages are wider ($wider)", wider > 1.2f)
        assertEquals("Keep Page Size keeps it", wider.toDouble(), AppSettings.continuousPageScale, 0.01)
        assertTrue(reader.holdsBack)
        reader.onBackHeld()
        settle()
        assertEquals(1f, stripScale(reader), 0.001f)
        assertEquals(1.0, AppSettings.continuousPageScale, 0.001)
    }
}
