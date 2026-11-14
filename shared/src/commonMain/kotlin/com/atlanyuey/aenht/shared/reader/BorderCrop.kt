package com.atlanyuey.aenht.shared.reader

import kotlin.math.abs

/**
 * Finds the plain white or black margins around a page, for the reader's
 * Auto Crop. Works on a small copy of the image ([pixels]: ARGB, row by
 * row) and answers the area to keep as left, top, right, bottom (right and
 * bottom exclusive) in that copy's coordinates, or null to keep the page
 * as it is (no plain margin, colored background, or nearly blank page).
 */
object BorderCrop {
    private const val TOLERANCE = 40
    /** A line is part of the margin when fewer of its pixels than this differ. */
    private const val INK = 0.006

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    fun find(pixels: IntArray, width: Int, height: Int): IntArray? {
        if (width < 16 || height < 16 || pixels.size < width * height) return null
        val corners = listOf(0, width - 1, (height - 1) * width, height * width - 1).map { luma(pixels[it]) }
        val background = corners.average().toInt()
        val white = background > 200 && corners.all { it > 180 }
        val black = background < 55 && corners.all { it < 75 }
        if (!white && !black) return null

        fun rowBlank(y: Int): Boolean {
            var ink = 0
            val limit = (width * INK).toInt().coerceAtLeast(1)
            for (x in 0 until width) if (abs(luma(pixels[y * width + x]) - background) > TOLERANCE && ++ink >= limit) return false
            return true
        }
        fun columnBlank(x: Int, top: Int, bottom: Int): Boolean {
            var ink = 0
            val limit = ((bottom - top) * INK).toInt().coerceAtLeast(1)
            for (y in top until bottom) if (abs(luma(pixels[y * width + x]) - background) > TOLERANCE && ++ink >= limit) return false
            return true
        }

        var top = 0
        while (top < height && rowBlank(top)) top++
        var bottom = height
        while (bottom > top && rowBlank(bottom - 1)) bottom--
        if (bottom - top < height / 2) return null
        var left = 0
        while (left < width && columnBlank(left, top, bottom)) left++
        var right = width
        while (right > left && columnBlank(right - 1, top, bottom)) right--
        if (right - left < width / 2) return null

        // A little of the margin stays, so the drawing doesn't touch the edge.
        val mx = width / 100 + 1
        val my = height / 100 + 1
        left = (left - mx).coerceAtLeast(0); right = (right + mx).coerceAtMost(width)
        top = (top - my).coerceAtLeast(0); bottom = (bottom + my).coerceAtMost(height)
        if (left == 0 && top == 0 && right == width && bottom == height) return null
        return intArrayOf(left, top, right, bottom)
    }
}
