package com.atlanyuey.aenht.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.filterNotNull

/**
 * Page thumbnails on gallery pages and the page each open reader is on:
 * the reader opens out of the thumbnail and closes back into the thumbnail
 * of the page it's on, and the row of pages behind it stays on that page.
 */
object ReaderThumbs {
    private val rects = mutableStateMapOf<Pair<Int, Int>, Rect>()
    private val pages = mutableStateMapOf<Int, Int>()

    /** Where the thumbnail of [page] is (window coordinates), if it's laid out. */
    fun rect(gallery: Int, page: Int): Rect? = rects[gallery to page]

    fun page(gallery: Int): Int? = pages[gallery]

    fun setPage(gallery: Int, page: Int) {
        if (pages[gallery] != page) pages[gallery] = page
    }

    internal fun put(gallery: Int, page: Int, rect: Rect?) {
        val key = gallery to page
        if (rect == null || rect.isEmpty) rects.remove(key) else if (rects[key] != rect) rects[key] = rect
    }
}

/** Marks a page thumbnail as where the reader zooms from and back to. */
fun Modifier.readerThumb(gallery: Int, page: Int): Modifier =
    onGloballyPositioned { ReaderThumbs.put(gallery, page, it.boundsInWindow()) }

/** Keeps a row of pages (item index = page index) on the page the reader is on, centred. */
@Composable
fun FollowReader(gallery: Int, state: LazyListState, count: Int) {
    LaunchedEffect(gallery, count) {
        snapshotFlow { ReaderThumbs.page(gallery) }.filterNotNull().collect { page ->
            if (page !in 0 until count) return@collect
            state.scrollToItem(page)
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == page } ?: return@collect
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            state.scrollBy(item.offset - (viewport - item.size) / 2f)
        }
    }
}
