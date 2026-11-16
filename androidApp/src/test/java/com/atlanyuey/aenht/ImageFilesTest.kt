package com.atlanyuey.aenht

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.atlanyuey.aenht.core.imageExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Downloaded pages (R005, D002, D003): a file cut off part way, or one with
 * the right first and last bytes but no picture, is not a page. Checked
 * with Android's own decoder (Robolectric's native graphics).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageFilesTest {
    private fun bitmap(): Bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(40, 120, 200))
        canvas.drawCircle(30f, 20f, 12f, Paint().apply { color = Color.WHITE })
    }

    private fun encoded(format: Bitmap.CompressFormat) =
        ByteArrayOutputStream().also { bitmap().compress(format, 90, it) }.toByteArray()

    /** GIF and BMP, which Android doesn't write: from the JDK's encoder. */
    private fun awt(format: String): ByteArray {
        val image = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private val whole: Map<String, ByteArray> by lazy {
        mapOf(
            "jpg" to encoded(Bitmap.CompressFormat.JPEG),
            "png" to encoded(Bitmap.CompressFormat.PNG),
            "webp" to encoded(Bitmap.CompressFormat.WEBP_LOSSY),
            "gif" to awt("gif"),
            "bmp" to awt("bmp"),
        )
    }

    private fun file(bytes: ByteArray): File = File.createTempFile("page", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun be(value: Long, size: Int) = ByteArray(size) { (value shr (8 * (size - 1 - it))).toByte() }
    private fun box(type: String, body: ByteArray) = be(8L + body.size, 4) + type.toByteArray() + body

    @Test
    fun wholePicturesKeepTheirType() {
        for ((type, picture) in whole) assertEquals(type, imageExtension(file(picture)))
        // A few bytes after the end of a JPEG (some cameras and sites add them).
        assertEquals("jpg", imageExtension(file(whole.getValue("jpg") + ByteArray(20))))
    }

    @Test
    fun cutOffPicturesAreNotPictures() {
        for ((type, picture) in whole) {
            assertNull(type, imageExtension(file(picture.copyOf(12))))
            assertNull(type, imageExtension(file(picture.copyOf(picture.size / 2))))
            assertNull(type, imageExtension(file(picture.copyOf(picture.size - 3))))
        }
        assertNull(imageExtension(file("<html>error</html>".toByteArray())))
        assertNull(imageExtension(File("/nonexistent/page.jpg")))
    }

    @Test
    fun theRightFirstAndLastBytesWithoutAPictureAreNotAPicture() {
        val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) +
            be(0, 4) + "IEND".toByteArray() + bytes(0xAE, 0x42, 0x60, 0x82)
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, 0, 0, 0, 0, 0xFF, 0xD9)
        val gif = "GIF89a".toByteArray() + bytes(1, 0, 1, 0, 0, 0, 0) + bytes(0x3B)
        val webp = "RIFF".toByteArray() + bytes(4, 0, 0, 0) + "WEBP".toByteArray()
        val avif = box("ftyp", "avif".toByteArray() + ByteArray(12))
        for (sample in listOf(png, jpeg, gif, webp, avif)) assertNull(imageExtension(file(sample)))
    }

    @Test
    fun aBoxLengthBeyondTheFileIsNotAPicture() {
        // A box that says it is far longer than the file (once overflowed the sum of lengths).
        val ftyp = box("ftyp", "avif".toByteArray() + ByteArray(12))
        val huge = be(1, 4) + "mdat".toByteArray() + be(Long.MAX_VALUE, 8)
        assertNull(imageExtension(file(ftyp + huge)))
        val negative = be(1, 4) + "mdat".toByteArray() + be(-1, 8)
        assertNull(imageExtension(file(ftyp + negative)))
        val longer = be(4096, 4) + "mdat".toByteArray() + ByteArray(32)
        assertNull(imageExtension(file(ftyp + longer)))
    }
}
