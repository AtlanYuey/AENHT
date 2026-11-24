package com.atlanyuey.aenht

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.screens.GalleryScreen
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.NavHost
import com.atlanyuey.aenht.ui.components.OverlayController
import com.atlanyuey.aenht.ui.components.OverlayHost
import com.atlanyuey.aenht.ui.components.prompt
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back with a menu, alert or sheet open closes it, not the page under it.
 * A tab's view, shown after the overlays' host, adds its Back handler later:
 * on Android the later one is asked first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class OverlayBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun backClosesAnOpenSheetBeforeThePage() {
        if (Net.interceptors.isEmpty()) Net.interceptors.add(FakeApi)
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(androidx.test.core.app.ApplicationProvider.getApplicationContext()).components { add(FakeImages) }.build())
        val tabs = Tabs()
        val overlays = OverlayController()
        val shown = mutableIntStateOf(0)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalOverlays provides overlays) {
                    val backdrop = rememberLayerBackdrop()
                    Box {
                        key(shown.intValue) { NavHost(tabs.all[shown.intValue]) }
                        OverlayHost(overlays, backdrop)
                    }
                }
            }
        }
        compose.waitForIdle()
        tabs.more.push(GalleryScreen(500001, null))
        // The Me tab chosen: its view (and its Back handler) made after the overlays' host.
        shown.intValue = 3
        compose.waitForIdle()
        compose.runOnUiThread { overlays.prompt("API Key", null, "", "Log In", "Cancel") {} }
        compose.waitForIdle()
        assertEquals(1, overlays.items.size)

        back()
        assertTrue("the sheet closed", overlays.items.isEmpty())
        assertEquals("the page under it stayed", 2, tabs.more.stack.size)

        back()
        assertEquals("then Back leaves the page", 1, tabs.more.stack.size)
    }
}
