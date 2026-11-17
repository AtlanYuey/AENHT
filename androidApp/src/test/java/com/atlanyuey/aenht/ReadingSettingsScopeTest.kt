package com.atlanyuey.aenht

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.DoublePage
import com.atlanyuey.aenht.core.ReadMode
import com.atlanyuey.aenht.screens.ReadingScreen
import com.atlanyuey.aenht.screens.readingSettingsTab
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ReadingSettingsScopeTest {
    @get:Rule val compose = createComposeRule()
    private var previous: String? = null

    private fun show(mode: ReadMode) {
        ScreenshotTest().setUp()
        previous = AppSettings.toJson()
        AppSettings.readMode = mode
        AppSettings.doublePage = DoublePage.Always
        AppSettings.autoScrollSecondsPerScreen = 10.0
        readingSettingsTab = 0
        val tabs = Tabs().also { it.more.push(ReadingScreen()) }
        val linked = mutableStateOf<GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linked, tabs, 3) } }
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
    }

    @After fun restoreSettings() { previous?.let { AppSettings.applyJson(it) } }

    @Test fun pageGapIsAvailableOnlyForContinuousReadingWithGaps() {
        show(ReadMode.LeftToRight)
        for (mode in listOf(ReadMode.LeftToRight, ReadMode.RightToLeft, ReadMode.VerticalPaged, ReadMode.Vertical, ReadMode.VerticalGap)) {
            compose.runOnIdle { AppSettings.readMode = mode }
            compose.waitForIdle()
            compose.onNodeWithText("Gap Between Pages").assertDoesNotExist()
            if (mode == ReadMode.VerticalGap) {
                compose.onNodeWithText("Page Gap").assertIsDisplayed()
            } else {
                compose.onNodeWithText("Page Gap").assertDoesNotExist()
            }
        }
        compose.onRoot().captureRoboImage("build/screens/auto-scroll-reading-gap.png")
    }

    @Test fun speedSliderReachesOneAndThirtySecondsAndShowsHalfSeconds() {
        show(ReadMode.Vertical)
        compose.onNodeWithText("Auto Scroll Speed").performScrollTo()
        val slider = compose.onNode(SemanticsMatcher("one-to-thirty-second speed range") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range == 1f..30f
        })
        for ((value, seconds) in listOf(1f to 30.0, 30f to 1.0, 29.5f to 1.5)) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            compose.waitForIdle()
            assertEquals(seconds, AppSettings.autoScrollSecondsPerScreen, 0.0)
        }
        // Exercise the touch path as well as accessibility actions: both ends must be reachable.
        slider.performTouchInput { click(Offset(1f, centerY)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(30.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
        slider.performTouchInput { click(Offset(width - 1f, centerY)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertEquals(1.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(29.5f) }
        compose.waitForIdle()
        compose.onNodeWithText("1.5 s / screen").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/screens/auto-scroll-speed-range.png")
    }

    @Test fun fastWarningIncludesThreeSecondsAndDragOptionIsBelowSpeed() {
        show(ReadMode.Vertical)
        val warning = "Speed may be too fast for comfortable reading"
        val option = "Continue Auto Scroll While Dragging"
        compose.onNodeWithText(option).assertIsDisplayed()
        val title = compose.onNodeWithText("Auto Scroll Speed").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithText(option).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(toggle.top > title.bottom)
        for (seconds in listOf(3.0, 2.5, 1.0)) {
            compose.runOnIdle { AppSettings.autoScrollSecondsPerScreen = seconds }
            compose.waitForIdle()
            compose.onNodeWithText(warning).assertIsDisplayed()
        }
        compose.onRoot().captureRoboImage("build/screens/auto-scroll-fast-warning.png")
        compose.runOnIdle { AppSettings.autoScrollSecondsPerScreen = 3.5 }
        compose.waitForIdle()
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.runOnIdle { AppSettings.readMode = ReadMode.LeftToRight }
        compose.waitForIdle()
        compose.onNodeWithText(option).assertDoesNotExist()
    }
}
