package com.atlanyuey.aenht

import com.atlanyuey.aenht.core.imageExtension
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 30, 31, 36])
class SupportedImageValidationTest {
    private fun be(value: Int) = ByteArray(4) { (value ushr (8 * (3 - it))).toByte() }
    private fun box(type: String, body: ByteArray) = be(body.size + 8) + type.toByteArray() + body

    @Test fun aContainerWithRandomDataAndNoImageIsNotACompletedPage() {
        // Correct box lengths, but no meta/pitm/iloc/iinf, dimensions, or coded AV1 picture.
        val data = box("ftyp", "avif".toByteArray() + ByteArray(12)) + box("mdat", ByteArray(32))
        val file = File.createTempFile("not-an-image", ".avif").apply { writeBytes(data); deleteOnExit() }
        val result = imageExtension(file)
        println("sdk=${android.os.Build.VERSION.SDK_INT} bytes=${data.size} recognized=$result")
        assertNull("A container with no image must not be counted as a finished download", result)
    }
}
