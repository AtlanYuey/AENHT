package com.atlanyuey.aenht

import com.atlanyuey.aenht.core.imageExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Downloaded pages: a file cut off part way is not a picture (R005). */
class ImageFilesTest {
    private fun picture(format: String): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private fun file(bytes: ByteArray): File = File.createTempFile("page", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    private fun be(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    private fun box(type: String, body: ByteArray) = be(8 + body.size) + type.toByteArray() + body

    @Test
    fun wholePicturesKeepTheirType() {
        assertEquals("jpg", imageExtension(file(picture("jpg"))))
        assertEquals("png", imageExtension(file(picture("png"))))
        assertEquals("gif", imageExtension(file(picture("gif"))))
        assertEquals("bmp", imageExtension(file(picture("bmp"))))
        // A few bytes after the end of a JPEG (some cameras and sites add them).
        assertEquals("jpg", imageExtension(file(picture("jpg") + ByteArray(20))))
        val webp = "RIFF".toByteArray() + le(4 + 20) + "WEBP".toByteArray() + ByteArray(20)
        assertEquals("webp", imageExtension(file(webp)))
        val avif = box("ftyp", "avif".toByteArray() + ByteArray(8)) + box("meta", ByteArray(30)) + box("mdat", ByteArray(100))
        assertEquals("avif", imageExtension(file(avif)))
    }

    @Test
    fun cutOffPicturesAreNotPictures() {
        for (format in listOf("jpg", "png", "gif", "bmp")) {
            val whole = picture(format)
            assertNull(format, imageExtension(file(whole.copyOf(12))))
            assertNull(format, imageExtension(file(whole.copyOf(whole.size / 2))))
            assertNull(format, imageExtension(file(whole.copyOf(whole.size - 3))))
        }
        val webp = "RIFF".toByteArray() + le(4 + 20) + "WEBP".toByteArray() + ByteArray(20)
        assertNull(imageExtension(file(webp.copyOf(webp.size - 1))))
        val avif = box("ftyp", "avif".toByteArray() + ByteArray(8)) + box("meta", ByteArray(30)) + box("mdat", ByteArray(100))
        assertNull(imageExtension(file(avif.copyOf(avif.size - 10))))
        assertNull(imageExtension(file("<html>error</html>".toByteArray())))
        assertNull(imageExtension(File("/nonexistent/page.jpg")))
    }
}
