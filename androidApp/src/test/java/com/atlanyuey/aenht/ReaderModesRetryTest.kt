package com.atlanyuey.aenht

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.screens.*
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.Tabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual ReaderScreen and Coil, including a second retry after another failure. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class ReaderModesRetryTest {
    @get:Rule val compose = createComposeRule()

    private fun exercise(mode: ReadMode, double: DoublePage) {
        // Reuse only the existing synthetic HTTP/cover fixture, not its retry test.
        ScreenshotTest().setUp()
        AppSettings.readMode = mode
        AppSettings.doublePage = double
        AppSettings.doublePageCover = false
        AppSettings.superResolution = SrMode.Off
        AppSettings.preloadPages = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val page = "https://example.invalid/0.jpg"
        val asked = AtomicInteger()
        val offline = AtomicBoolean(true)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(Interceptor { chain ->
                if (chain.request.data.toString() == page) {
                    asked.incrementAndGet()
                    if (offline.get()) return@Interceptor ErrorResult(null, chain.request, IOException("offline"))
                }
                SuccessResult(Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).asImage(), chain.request, DataSource.NETWORK)
            })
        }.build())
        val tabs = Tabs()
        val count = if (double == DoublePage.Always) 2 else 1
        tabs.home.push(ReaderScreen(ReaderSource(
            980001, "Audit synthetic reader", (0 until count).map { ReaderPage(it, "https://example.invalid/$it.jpg", .7f) },
            "https://nhentai.net/g/980001/", GallerySummary.from(GalleryItem(980001, numPages = count)),
        ), 0))
        val linked = mutableStateOf<GalleryLink?>(null)
        try {
            compose.setContent { AppTheme { RootView(linked, tabs, 0) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().size == 1 }
            val first = asked.get()
            compose.onNodeWithText("Reload Page").performClick()
            compose.waitUntil(10_000) { asked.get() > first && compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().size == 1 }
            val second = asked.get()
            offline.set(false)
            compose.onNodeWithText("Reload Page").performClick()
            compose.waitUntil(10_000) { asked.get() > second && compose.onAllNodesWithText("Reload Page").fetchSemanticsNodes().isEmpty() }
            println("mode=$mode double=$double actual_loader_requests=${asked.get()} first=$first second=$second")
        } finally {
            AppSettings.readMode = ReadMode.LeftToRight
            AppSettings.doublePage = DoublePage.Off
        }
    }

    @Test fun doublePageCanFailAndRetryTwice() = exercise(ReadMode.LeftToRight, DoublePage.Always)
    @Test fun continuousPageCanFailAndRetryTwice() = exercise(ReadMode.Vertical, DoublePage.Off)
}
