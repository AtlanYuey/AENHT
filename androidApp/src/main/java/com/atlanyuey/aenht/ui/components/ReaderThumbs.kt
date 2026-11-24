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
import kotlinx.coroutines.launch

/**
 * Page thumbnails on gallery pages and the page each open reader is on:
 * the reader opens out of the thumbnail and closes back into the thumbnail
 * of the page it's on, and the row of pages behind it stays on that page.
 */
object ReaderThumbs {
    // Galleries by their storage key ("nh:123", "eh:123:token"): the two sites' numbers overlap.
    private val rects = mutableStateMapOf<Pair<String, Int>, Rect>()
    private val pages = mutableStateMapOf<String, Int>()

    /** Where the thumbnail of [page] is (window coordinates), if it's laid out. */
    fun rect(gallery: String, page: Int): Rect? = rects[gallery to page]

    fun page(gallery: String): Int? = pages[gallery]

    fun setPage(gallery: String, page: Int) {
        if (pages[gallery] != page) pages[gallery] = page
    }

    internal fun put(gallery: String, page: Int, rect: Rect?) {
        val key = gallery to page
        if (rect == null || rect.isEmpty) rects.remove(key) else if (rects[key] != rect) rects[key] = rect
    }
}

/** Marks a page thumbnail as where the reader zooms from and back to. */
fun Modifier.readerThumb(gallery: String, page: Int): Modifier =
    onGloballyPositioned { ReaderThumbs.put(gallery, page, it.boundsInWindow()) }

/** Keeps a row of pages (item index = page index) on the page the reader is on, centred. */
@Composable
fun FollowReader(gallery: String, state: LazyListState, count: Int) {
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

/**
 * Keeps a grid of pages on the page the reader is on, centred: [index]
 * gives a page's item index in the grid (null while it isn't there yet).
 */
@Composable
fun FollowReader(gallery: String, state: androidx.compose.foundation.lazy.grid.LazyGridState, count: Int, index: (Int) -> Int?) {
    LaunchedEffect(gallery, count) {
        snapshotFlow { ReaderThumbs.page(gallery) }.filterNotNull().collect { page ->
            val target = index(page) ?: return@collect
            state.scrollToItem(target)
            val info = state.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@collect
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            state.scrollBy(item.offset.y - (viewport - item.size.height) / 2f)
        }
    }
}

/**
 * The title of a row of pages, tapped: the row goes to the first page;
 * tapped again, back to where it was (like the Home tab). Scrolling by
 * hand starts over.
 */
@Composable
fun rememberRowJump(state: LazyListState): () -> Unit {
    val scope = com.atlanyuey.aenht.core.rememberSafeScope()
    val saved = androidx.compose.runtime.remember { arrayOfNulls<Pair<Int, Int>>(1) }
    val jumping = androidx.compose.runtime.remember { booleanArrayOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling -> if (scrolling && !jumping[0]) saved[0] = null }
    }
    return {
        scope.launch {
            jumping[0] = true
            try {
                val back = saved[0]
                if (back != null) {
                    saved[0] = null
                    state.animateScrollToItem(back.first, back.second)
                } else {
                    saved[0] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
                    state.animateScrollToItem(0)
                }
            } finally {
                jumping[0] = false
            }
        }
    }
}
