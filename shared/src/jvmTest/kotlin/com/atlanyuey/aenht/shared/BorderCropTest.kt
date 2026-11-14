package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.reader.BorderCrop
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class BorderCropTest {
    private fun page(w: Int, h: Int, background: Int, ink: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (ink(i % w, i / w)) 0xFF404040.toInt() else background }

    @Test
    fun trimsWhiteMargins() {
        val p = page(100, 140, 0xFFFFFFFF.toInt()) { x, y -> x in 20 until 80 && y in 10 until 130 }
        assertContentEquals(intArrayOf(18, 8, 82, 132), BorderCrop.find(p, 100, 140))
    }

    @Test
    fun keepsPagesWithoutPlainMargins() {
        assertNull(BorderCrop.find(page(100, 140, 0xFF3366CC.toInt()) { x, _ -> x in 20 until 80 }, 100, 140))
        assertNull(BorderCrop.find(page(100, 140, 0xFFFFFFFF.toInt()) { x, y -> x in 40 until 60 && y in 60 until 70 }, 100, 140))
        assertNull(BorderCrop.find(page(100, 140, 0xFF000000.toInt()) { _, _ -> true }.also { it[0] = 0xFF000000.toInt() }, 100, 140))
    }
}
