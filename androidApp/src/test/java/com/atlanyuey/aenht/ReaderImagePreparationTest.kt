package com.atlanyuey.aenht

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.request.SuccessResult
import com.atlanyuey.aenht.core.SrMode
import com.atlanyuey.aenht.core.SrModel
import com.atlanyuey.aenht.screens.ReaderImageOptions
import com.atlanyuey.aenht.screens.upcomingReaderPages
import com.atlanyuey.aenht.shared.reader.Spreads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReaderImagePreparationTest {
    @Test fun countsTwoTurnsRatherThanTwoFiles() {
        assertEquals(listOf(1, 2), upcomingReaderPages(0, 12, null).map { it.index })
        val spreads = Spreads(12, cover = true, widePages = emptySet())
        assertEquals(listOf(1, 2, 3, 4), upcomingReaderPages(0, 12, spreads).map { it.index })
        assertEquals(emptyList<Int>(), upcomingReaderPages(spreads.count - 1, 12, spreads).map { it.index })
    }

    @Test fun overlappingSpreadsNeverPrepareTheSamePageTwice() {
        val spreads = Spreads(12, cover = false, widePages = emptySet(), singleStep = true)
        val next = upcomingReaderPages(0, 12, spreads)
        assertEquals(listOf(1, 2, 3), next.map { it.index })
        assertEquals(listOf(2, 2, 2), next.map { it.pagesPerTurn })
    }

    @Test fun downloadedPagesUseThePreparedDecodeInEveryLayout() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("reader-preparation", ".png", context.cacheDir)
        val loader = ImageLoader.Builder(context).build()
        try {
            file.outputStream().use { stream ->
                Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            val page = ReaderImageOptions(IntSize(1000, 1800), 12, continuous = false, crop = false, SrMode.Auto, SrModel.Standard)
            for ((options, pagesPerTurn) in listOf(page to 1, page to 2, page.copy(continuous = true) to 1)) {
                loader.memoryCache?.clear()
                val prepared = loader.execute(options.request(context, file, pagesPerTurn).build()) as SuccessResult
                val displayed = loader.execute(options.request(context, file, pagesPerTurn).build()) as SuccessResult
                assertEquals(DataSource.MEMORY_CACHE, displayed.dataSource)
                assertEquals(prepared.memoryCacheKey, displayed.memoryCacheKey)
            }
        } finally {
            loader.shutdown()
            file.delete()
        }
    }
}
