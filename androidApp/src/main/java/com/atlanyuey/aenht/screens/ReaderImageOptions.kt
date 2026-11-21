package com.atlanyuey.aenht.screens

import android.content.Context
import androidx.compose.ui.unit.IntSize
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import com.atlanyuey.aenht.core.AutoCropTransformation
import com.atlanyuey.aenht.core.SrMode
import com.atlanyuey.aenht.core.SrModel
import com.atlanyuey.aenht.shared.reader.Spreads

internal data class ReaderPageInTurn(val index: Int, val pagesPerTurn: Int)

internal fun pagesInTurn(index: Int, count: Int, spreads: Spreads?): List<ReaderPageInTurn> {
    val pages = spreads?.pages(index) ?: listOfNotNull(index.takeIf { it in 0 until count })
    return pages.map { ReaderPageInTurn(it, pages.size) }
}

internal fun upcomingReaderPages(after: Int, count: Int, spreads: Spreads?, turns: Int = 2): List<ReaderPageInTurn> =
    (after + 1..after + turns).flatMap { pagesInTurn(it, count, spreads) }.distinctBy { it.index }

/**
 * Pages to have ready ahead of auto scroll at [secondsPerScreen]: about eight
 * seconds of it (two at least, ten at most), so a fast scroll never reaches a
 * page that is still being loaded or decoded.
 */
internal fun pagesAhead(autoScrolling: Boolean, secondsPerScreen: Double): Int {
    if (!autoScrolling || !secondsPerScreen.isFinite() || secondsPerScreen <= 0) return 2
    return kotlin.math.ceil(8 / secondsPerScreen).toInt().plus(1).coerceIn(2, 10)
}

internal data class ReaderImageOptions(
    val viewport: IntSize,
    val gapPixels: Int,
    val continuous: Boolean,
    val crop: Boolean,
    val mode: SrMode,
    val model: SrModel,
) {
    fun target(pagesPerTurn: Int): IntSize = when {
        continuous -> IntSize(viewport.width, Int.MAX_VALUE)
        pagesPerTurn == 2 -> IntSize((viewport.width - gapPixels) / 2, viewport.height)
        else -> viewport
    }

    fun enhancementKey(data: Any): String = if (continuous) "$data|strip" else "$data|$crop"

    // A downsampled long image is not a low-resolution source.
    fun shouldEnhance(result: SuccessResult): Boolean =
        mode == SrMode.Always || (mode == SrMode.Auto && !(continuous && result.isSampled))

    fun request(context: Context, data: Any?, pagesPerTurn: Int): ImageRequest.Builder {
        val width = if (continuous) 1400 else target(pagesPerTurn).width
        val height = if (continuous) 8192 else viewport.height
        return ImageRequest.Builder(context).data(data).scale(Scale.FIT).precision(Precision.INEXACT).apply {
            if (width > 0 && height > 0) size(width, height)
            if (mode != SrMode.Off) allowHardware(false)
            if (crop) transformations(AutoCropTransformation())
        }
    }
}
