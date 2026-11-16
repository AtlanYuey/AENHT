package com.atlanyuey.aenht

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.screens.DownloadsScreen
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class DownloadNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun verify() {
        ScreenshotTest().setUp()
        AppSettings.libraryStyle = LibraryStyle.List
        Downloads.records.clear()
        Downloads.records.add(DownloadRecord(
            id = 990013, mediaId = "9", title = "Synthetic paused download", thumbnail = null,
            thumbWidth = 0, thumbHeight = 0,
            pages = listOf(DownloadPage(1, "https://fixture.invalid/page.png", "0001.png", 2, 2)),
            completed = 0, state = DownloadRecord.PAUSED, added = 0L,
        ))
        val tabs = Tabs().also { it.more.push(DownloadsScreen()) }
        val linked = mutableStateOf<GalleryLink?>(null)
        try {
            compose.setContent { AppTheme { RootView(linked, tabs, 3) } }
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            val title = compose.onNodeWithText("Downloads").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val select = compose.onNodeWithText("Select").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Navigation title overlaps Select: title=$title select=$select", title.right <= select.left)
        } finally {
            Downloads.records.clear()
            AppSettings.libraryStyle = LibraryStyle.Cards
        }
    }

    @Test fun titleAndSelectDoNotOverlapAt393Dp() = verify()

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun titleAndSelectDoNotOverlapAt360Dp() = verify()
}
