package com.atlanyuey.aenht

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New eecdc82 home controls, with the real UI and synthetic HTTP only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class HomeControlsRegressionTest {
    @get:Rule val compose = createComposeRule()

    private fun show(site: Site, onlyOneSite: Boolean) {
        ScreenshotTest().setUp()
        AppSettings.nhentaiEnabled = !onlyOneSite || site == Site.NHENTAI
        AppSettings.ehentaiEnabled = !onlyOneSite || site == Site.EHENTAI
        AppSettings.homeSite = site
        val linked = mutableStateOf<GalleryLink?>(null)
        val tabs = Tabs()
        compose.setContent { AppTheme { RootView(linked, tabs, 0) } }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
    }

    @Test fun aSingleNhSiteHasNoTitleMenuAndRandomFailureIsVisible() {
        show(Site.NHENTAI, true)
        compose.onNode(hasText("nhentai") and !hasClickAction()).assertExists()
        compose.onNodeWithContentDescription("Random Gallery").assertIsDisplayed().performClick()
        // Existing FakeApi deliberately returns [] for /galleries/random: invalid response.
        // This is synthetic failure feedback, not a live request or success-navigation test.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't Load").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Couldn't Load").assertIsDisplayed()
    }

    @Test fun aSingleEhSiteHasNoTitleMenuOrRandomButton() {
        show(Site.EHENTAI, true)
        compose.onNode(hasText("E-Hentai") and !hasClickAction()).assertExists()
        compose.onNodeWithContentDescription("Random Gallery").assertDoesNotExist()
    }

    @Test fun twoSitesCanSwitchThroughTheTitle() {
        show(Site.NHENTAI, false)
        // Both the title menu and bottom tab are clickable; the title is the top one.
        val nodes = compose.onAllNodes(hasText("nhentai") and hasClickAction()).fetchSemanticsNodes()
        val topId = nodes.minBy { it.boundsInRoot.top }.id
        compose.onNode(SemanticsMatcher("top title") { it.id == topId }).performClick()
        compose.onNodeWithText("E-Hentai").performClick()
        compose.waitUntil(10_000) { AppSettings.homeSite == Site.EHENTAI }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Random Gallery").assertDoesNotExist()
    }
}
