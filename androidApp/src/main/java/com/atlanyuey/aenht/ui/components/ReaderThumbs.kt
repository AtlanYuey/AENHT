package com.atlanyuey.aenht.ui.components

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** A title on screen (window coordinates), as it's drawn: its text flies between a card and a page. */
class TitleSpot {
    var rect by androidx.compose.runtime.mutableStateOf<Rect?>(null)
    var text = ""
    var style = androidx.compose.ui.text.TextStyle.Default
    var maxLines = 2
}

/** Where one card's cover (and title) is on screen (window coordinates), while the card is shown. */
class CoverSpot {
    var rect by androidx.compose.runtime.mutableStateOf<Rect?>(null)
    val title = TitleSpot()
    internal var attached = true
}

/**
 * The covers of the cards a gallery's page was opened from, and of the pages'
 * headers ("nh:123", "eh:123"): the cover itself flies from the card to the
 * page's header, and back.
 */
object GalleryCovers {
    private val sources = mutableStateMapOf<String, CoverSpot>()
    private val headers = mutableStateMapOf<String, Rect>()
    private val headerTitles = mutableStateMapOf<String, TitleSpot>()

    /** The gallery whose cover is flying now (both its covers on screen are hidden meanwhile). */
    var flying by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun key(key: com.atlanyuey.aenht.shared.site.GalleryKey) = "${key.site.key}:${key.id}"

    /** Where the card [key]'s page was opened from is (window coordinates), if it's shown. */
    fun rect(key: com.atlanyuey.aenht.shared.site.GalleryKey): Rect? = sources[key(key)]?.rect

    /** The title of the card [key]'s page was opened from (if the card has one). */
    fun cardTitle(key: com.atlanyuey.aenht.shared.site.GalleryKey): TitleSpot? = sources[key(key)]?.title?.takeIf { it.rect != null }

    /** The title in the header of [key]'s page, while it's laid out. */
    fun headerTitle(key: com.atlanyuey.aenht.shared.site.GalleryKey): TitleSpot? = headerTitles[key(key)]?.takeIf { it.rect != null }

    internal fun putHeaderTitle(key: String, spot: TitleSpot?) {
        if (spot == null) headerTitles.remove(key) else if (headerTitles[key] !== spot) headerTitles[key] = spot
    }

    /** Where the cover in the header of [key]'s page is, while it's laid out. */
    fun headerRect(key: com.atlanyuey.aenht.shared.site.GalleryKey): Rect? = headers[key(key)]

    /** [key]'s page is opened from the card whose cover is [spot]. */
    fun opened(key: com.atlanyuey.aenht.shared.site.GalleryKey, spot: CoverSpot) {
        sources[key(key)] = spot
    }

    /** A card laid out: it takes the place of the one its page was opened from when that one is gone (back to the list). */
    internal fun relink(key: String, spot: CoverSpot) {
        val current = sources[key] ?: return
        if (current !== spot && !current.attached) sources[key] = spot
    }

    internal fun putHeader(key: String, rect: Rect?) {
        if (rect == null || rect.isEmpty) headers.remove(key) else if (headers[key] != rect) headers[key] = rect
    }
}

/** A card's cover: where its gallery's page opens from and closes back into; hidden while it flies. */
fun Modifier.galleryCover(item: com.atlanyuey.aenht.shared.site.GallerySummary, spot: CoverSpot): Modifier {
    val key = GalleryCovers.key(item.key)
    return onGloballyPositioned {
        spot.rect = it.boundsInWindow()
        GalleryCovers.relink(key, spot)
    }.graphicsLayer { if (GalleryCovers.flying == key) alpha = 0f }
}

/** A card's title: it flies to the page's title; hidden while it flies. */
fun Modifier.galleryTitle(
    item: com.atlanyuey.aenht.shared.site.GallerySummary, spot: CoverSpot,
    text: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int,
): Modifier {
    val key = GalleryCovers.key(item.key)
    spot.title.text = text
    spot.title.style = style
    spot.title.maxLines = maxLines
    return onGloballyPositioned { spot.title.rect = it.boundsInWindow() }
        .graphicsLayer { if (GalleryCovers.flying == key) alpha = 0f }
}

/** The title in a gallery page's header: where the card's title flies to; hidden while it flies. */
@androidx.compose.runtime.Composable
fun Modifier.galleryHeaderTitle(
    galleryKey: com.atlanyuey.aenht.shared.site.GalleryKey,
    text: String, style: androidx.compose.ui.text.TextStyle, maxLines: Int,
): Modifier {
    val key = GalleryCovers.key(galleryKey)
    val spot = androidx.compose.runtime.remember(key) { TitleSpot() }
    spot.text = text
    spot.style = style
    spot.maxLines = maxLines
    androidx.compose.runtime.DisposableEffect(key) {
        GalleryCovers.putHeaderTitle(key, spot)
        onDispose { GalleryCovers.putHeaderTitle(key, null) }
    }
    return onGloballyPositioned { spot.rect = it.boundsInWindow() }
        .graphicsLayer { if (GalleryCovers.flying == key) alpha = 0f }
}

/** The cover in a gallery page's header: where the card's cover flies to; hidden while it flies. */
@androidx.compose.runtime.Composable
fun Modifier.galleryHeaderCover(galleryKey: com.atlanyuey.aenht.shared.site.GalleryKey): Modifier {
    val key = GalleryCovers.key(galleryKey)
    androidx.compose.runtime.DisposableEffect(key) { onDispose { GalleryCovers.putHeader(key, null) } }
    return onGloballyPositioned { GalleryCovers.putHeader(key, it.boundsInWindow()) }
        .graphicsLayer { if (GalleryCovers.flying == key) alpha = 0f }
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
