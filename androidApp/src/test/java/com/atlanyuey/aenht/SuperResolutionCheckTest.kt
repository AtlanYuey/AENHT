package com.atlanyuey.aenht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.atlanyuey.aenht.core.SuperResolution
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** A super-resolution result is used only when it is the page enlarged (not a GPU's noise). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SuperResolutionCheckTest {
    private fun page(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.rgb(30, 40, 60) }
        // Panels and text-like strokes, like a manga page.
        for (i in 0 until 6) canvas.drawRect(10f + i * 20, 20f + i * 30, 60f + i * 20, 30f + i * 30, paint)
        paint.color = Color.rgb(200, 80, 90)
        canvas.drawCircle(width * 0.6f, height * 0.4f, width * 0.2f, paint)
        return bitmap
    }

    @Test fun thePageEnlargedPasses() {
        val input = page(160, 240)
        val enlarged = Bitmap.createScaledBitmap(input, 640, 960, true)
        assertTrue(SuperResolution.looksLike(enlarged, input))
    }

    @Test fun noiseIsRejected() {
        val input = page(160, 240)
        val noise = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        val random = Random(3)
        val pixels = IntArray(640 * 960) { random.nextInt() or (0xFF shl 24) }
        noise.setPixels(pixels, 0, 640, 0, 0, 640, 960)
        assertFalse(SuperResolution.looksLike(noise, input))
    }

    @Test fun stripesOfWrongTilesAreRejected() {
        // Tiles out of place: horizontal bands of colour where the page is mostly white.
        val input = page(160, 240)
        val broken = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(broken)
        val colors = listOf(Color.MAGENTA, Color.BLUE, Color.GREEN, Color.RED)
        for (y in 0 until 960 step 12) canvas.drawRect(0f, y.toFloat(), 640f, y + 12f, Paint().apply { color = colors[(y / 12) % 4] })
        assertFalse(SuperResolution.looksLike(broken, input))
    }
}
