package com.atlanyuey.aenht.core

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import com.atlanyuey.aenht.shared.reader.BorderCrop

/** Reader › Auto Crop: cuts plain white / black margins off a page (see BorderCrop). */
class AutoCropTransformation : Transformation() {
    override val cacheKey: String = "autoCrop1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // The margins are found on a small copy, then cut from the page.
        val scale = minOf(1f, 256f / maxOf(input.width, input.height))
        val w = maxOf(1, (input.width * scale).toInt())
        val h = maxOf(1, (input.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(input.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val box = BorderCrop.find(pixels, w, h) ?: return input
        val left = (box[0] / scale).toInt().coerceIn(0, input.width - 1)
        val top = (box[1] / scale).toInt().coerceIn(0, input.height - 1)
        val right = (box[2] / scale).toInt().coerceIn(left + 1, input.width)
        val bottom = (box[3] / scale).toInt().coerceIn(top + 1, input.height)
        return Bitmap.createBitmap(input, left, top, right - left, bottom - top)
    }
}
