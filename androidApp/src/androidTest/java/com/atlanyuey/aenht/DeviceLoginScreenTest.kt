package com.atlanyuey.aenht

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlanyuey.aenht.screens.EhLoginScreen
import com.atlanyuey.aenht.screens.LoginScreen
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.components.Screen
import com.atlanyuey.aenht.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The login pages (a WebView under the app's glass bar) on a real Android:
 * they open without crashing, and the page under the bar doesn't flicker.
 * Each samples the screen 50 times over 10 seconds; the brightness of the
 * part under the bar is logged (tag AENHT-SCREEN) and must not keep jumping.
 */
@RunWith(AndroidJUnit4::class)
class DeviceLoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(screen: Screen) {
        val tabs = Tabs().also { it.home.push(screen) }
        val linked = mutableStateOf<GalleryLink?>(null)
        compose.setContent { AppTheme { RootView(linked, tabs, 0) } }
        compose.waitForIdle()
    }

    /** Mean brightness (0–255) of the screen's middle band, under the bar. */
    private fun brightness(): Double {
        val shot: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return -1.0
        val w = shot.width
        val h = shot.height
        var sum = 0L
        var n = 0
        for (y in (h * 0.3).toInt() until (h * 0.7).toInt() step 8) for (x in 0 until w step 8) {
            val c = shot.getPixel(x, y)
            sum += ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
            n++
        }
        shot.recycle()
        return sum.toDouble() / n
    }

    /** Samples the screen; how many times it jumped (back and forth) by more than [jump]. */
    private fun watch(name: String, jump: Double = 40.0): Int {
        Thread.sleep(6000) // The page loads.
        val samples = (0 until 50).map { Thread.sleep(200); brightness() }
        Log.i("AENHT-SCREEN", "$name brightness: ${samples.joinToString { "%.0f".format(it) }}")
        return samples.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) > jump }
    }

    @Test fun nhentaiLoginOpensAndHoldsStill() {
        show(LoginScreen())
        val jumps = watch("nhentai login")
        Log.i("AENHT-SCREEN", "nhentai login jumps: $jumps")
        assertTrue("the login page flickers ($jumps jumps in 10 s)", jumps <= 4)
    }

    @Test fun ehentaiLoginOpensAndHoldsStill() {
        show(EhLoginScreen())
        val jumps = watch("E-Hentai login")
        Log.i("AENHT-SCREEN", "E-Hentai login jumps: $jumps")
        assertTrue("the login page flickers ($jumps jumps in 10 s)", jumps <= 4)
    }
}
