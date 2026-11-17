package com.atlanyuey.aenht.screens

import com.atlanyuey.aenht.ui.components.LocalNavigator
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import com.atlanyuey.aenht.ui.components.prompt
import coil3.request.allowHardware
import coil3.toBitmap
import coil3.asImage
import com.atlanyuey.aenht.ui.components.ButtonRow
import coil3.request.crossfade
import coil3.request.placeholder
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.asComposeRenderEffect

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.input.pointer.PointerEventType
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.HistoryStore
import com.atlanyuey.aenht.core.L10n
import com.atlanyuey.aenht.core.ReadMode
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.BackButton
import com.atlanyuey.aenht.ui.components.ActivityIndicator
import com.atlanyuey.aenht.ui.components.FooterText
import com.atlanyuey.aenht.ui.components.GlassCircleButton
import com.atlanyuey.aenht.ui.components.GlassSlider
import com.atlanyuey.aenht.ui.components.ListRow
import com.atlanyuey.aenht.ui.components.LocalBackdrop
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.MenuButton
import com.atlanyuey.aenht.ui.components.MenuItem
import com.atlanyuey.aenht.ui.components.PickerRow
import com.atlanyuey.aenht.ui.components.Screen
import com.atlanyuey.aenht.ui.components.Stepper
import com.atlanyuey.aenht.ui.components.TextButton
import com.atlanyuey.aenht.ui.components.ToggleRow
import com.atlanyuey.aenht.ui.components.section
import com.atlanyuey.aenht.ui.components.RowSeparator
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.components.glass
import com.atlanyuey.aenht.ui.components.GlassStyle
import com.atlanyuey.aenht.ui.components.shareLink
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.DarkColors
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.LocalColors
import com.atlanyuey.aenht.ui.theme.LocalIsDark
import com.atlanyuey.aenht.ui.theme.Type
import com.atlanyuey.aenht.ui.theme.color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.zoomable
import coil3.request.transformations
import com.atlanyuey.aenht.shared.reader.Spreads
import com.atlanyuey.aenht.core.DoublePage
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.SideEffect
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.abs

val ReadMode.icon
    get() = when (this) {
        ReadMode.LeftToRight -> Icons.LeftToRight
        ReadMode.RightToLeft -> Icons.RightToLeft
        ReadMode.VerticalPaged -> Icons.TopToBottom
        ReadMode.Vertical -> Icons.Continuous
        ReadMode.VerticalGap -> Icons.ContinuousGaps
    }

/** The reader on screen turns pages with the volume keys (MainActivity forwards them). */
object ReaderKeys {
    /** Turns forward (true) or back; null when no reader is open. */
    var handler: ((Boolean) -> Unit)? = null
}

/** Grayscale and / or inverted colors for the pages (Android 12+). */
private fun pageColorEffect(grayscale: Boolean, invert: Boolean): androidx.compose.ui.graphics.RenderEffect? {
    if ((!grayscale && !invert) || android.os.Build.VERSION.SDK_INT < 31) return null
    val matrix = android.graphics.ColorMatrix()
    if (grayscale) matrix.setSaturation(0f)
    if (invert) {
        matrix.postConcat(android.graphics.ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    return android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix)).asComposeRenderEffect()
}

/** Which taps turn the page: the outer 30 % on each side, when enabled. */
/**
 * What a tap at ([fx], [fy]), fractions of the page, does: true turns
 * forward, false back, null nothing (the middle). Left and right edges or
 * top and bottom (Tap Zones).
 */
private fun tapTurn(fx: Float, fy: Float, rtl: Boolean): Boolean? {
    if (!AppSettings.tapToTurnPages) return null
    val forward = when (AppSettings.tapZones) {
        com.atlanyuey.aenht.core.TapZones.LeftRight -> when {
            fx < 0.3f -> rtl
            fx > 0.7f -> !rtl
            else -> return null
        }
        com.atlanyuey.aenht.core.TapZones.TopBottom -> when {
            fy < 0.3f -> false
            fy > 0.7f -> true
            else -> return null
        }
    }
    return forward
}

class ReaderScreen(private val source: ReaderSource, start: Int) : Screen() {
    /** Opens out of, and closes into, the thumbnail of the page it's on (when it's on screen). */
    override fun zoomTarget() = com.atlanyuey.aenht.ui.components.ReaderThumbs.rect(source.thumbsKey, page)

    override val hidesTabBar: Boolean get() = true

    private var page by mutableIntStateOf(start.coerceIn(0, maxOf(0, source.pages.size - 1)))
    private var chromeVisible by mutableStateOf(true)
    private var autoRead by mutableStateOf(false)
    /** Bumped by every touch: the controls hide 1 s after the last one. */
    private var chromeActivity by mutableIntStateOf(0)
    /** A finger is on the screen (dragging the slider, holding a button…). */
    private var touching by mutableStateOf(false)
    /** Pages side by side (double page), or null for one page at a time. */
    private var spreads by mutableStateOf<Spreads?>(null)
    private val pager = PagerState(currentPage = page) { spreads?.count ?: source.pages.size }
    private val list = LazyListState(firstVisibleItemIndex = page)
    private val count get() = source.pages.size

    private val isRtl get() = AppSettings.readMode == ReadMode.RightToLeft

    /** The reader's composition scope (pager animations need its frame clock). */
    private var uiScope: kotlinx.coroutines.CoroutineScope = scope

    /** What the pages show, recorded each frame (a fade turn starts from it). */
    private var pagesLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    /** Without the page turn animation: the page before a turn, fading out over the new one. */
    private var fadeCover by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private val fadeAlpha = androidx.compose.animation.core.Animatable(0f)
    private var fadeJob: Job? = null

    /** The pager's page (a spread in double page) showing [page]. */
    private fun slot(page: Int) = spreads?.slotOf(page) ?: page
    private fun pageOfSlot(slot: Int) = spreads?.firstPage(slot) ?: slot

    /** The page one turn away from [base] (-1 past the ends). */
    private fun step(base: Int, forward: Boolean): Int =
        spreads?.step(base, if (forward) 1 else -1) ?: (if (forward) base + 1 else base - 1)

    private fun record() {
        HistoryStore.record(source.summary, page)
    }

    private fun go(target: Int) {
        if (target !in 0 until count) return
        val paged = AppSettings.readMode.isPaged
        val animate = AppSettings.animatePageTurns
        if (paged && !animate) {
            // A turn during another one ends that one and starts its own.
            fadeJob?.cancel()
            fadeJob = uiScope.launch { fadeTo(target) }
        } else {
            uiScope.launch {
                if (paged) {
                    // A later turn takes over from wherever this one has got to.
                    pager.animateScrollToPage(slot(target))
                } else {
                    if (animate) list.animateScrollToItem(target) else list.scrollToItem(target)
                }
            }
        }
        page = target
    }

    /**
     * Shows [target] in place: the page shown now fades out over it (no
     * motion). With [sweep] (a swipe) it fades away from one side to the
     * other with a soft edge, the way the finger went.
     */
    private suspend fun fadeTo(target: Int, sweep: Sweep? = null) {
        val cover = runCatching { pagesLayer?.toImageBitmap() }.getOrNull()
        pager.scrollToPage(slot(target))
        if (cover == null) return
        fadeCover = cover
        fadeSweep = sweep
        try {
            fadeAlpha.snapTo(1f)
            fadeAlpha.animateTo(0f, androidx.compose.animation.core.tween(if (sweep != null) 320 else 250))
        } finally {
            if (fadeCover === cover) fadeCover = null
        }
    }

    /** Which way a swipe went: across ([vertical] false) or up and down; [toStart]: left or up. */
    private data class Sweep(val vertical: Boolean, val toStart: Boolean)

    /** The way the fade of a swipe turn sweeps (null: the page fades as a whole). */
    private var fadeSweep by mutableStateOf<Sweep?>(null)

    /**
     * Fade on Swipe: a swipe the way the pages go turns to the next or the
     * previous page (by its direction) with a fade sweeping the way it went.
     * Answers whether the swipe was one.
     */
    private fun swipeTurn(delta: Offset, minimum: Float): Boolean {
        val vertical = AppSettings.readMode == ReadMode.VerticalPaged
        val along = if (vertical) delta.y else delta.x
        val across = if (vertical) delta.x else delta.y
        if (kotlin.math.abs(along) < minimum || kotlin.math.abs(along) < 1.5f * kotlin.math.abs(across)) return false
        // Left (or up) is forward, except right to left.
        val forward = if (isRtl) along > 0 else along < 0
        val target = step(turnBase, forward)
        chromeVisible = false
        if (target !in 0 until count) return true
        fadeJob?.cancel()
        fadeJob = uiScope.launch { fadeTo(target, Sweep(vertical, toStart = along < 0)) }
        page = target
        return true
    }

    /** The page a turn starts from (the target while a turn is running). */
    private val turnBase: Int get() = when {
        fadeJob?.isActive == true -> page
        pager.isScrollInProgress -> pageOfSlot(pager.targetPage)
        else -> if (slot(page) == pager.currentPage) page else pageOfSlot(pager.currentPage)
    }

    private fun turn(forward: Boolean) {
        // Turning pages from the edges hides the controls.
        chromeVisible = false
        val base = if (AppSettings.readMode.isPaged) turnBase else page
        go(step(base, forward))
    }

    /** What the swipes the pages don't use do (Reading settings › Controls). */
    private var gestureAction: ((com.atlanyuey.aenht.core.ReaderGesture) -> Unit)? = null

    /** A swipe of [delta]: up / down in the left-right modes, left / right in the top-to-bottom ones. */
    /** The last touch or volume key (Keep Screen On counts from it). */
    private var lastTouch = 0L

    /**
     * A long, straight swipe ([min] at least) does what the Controls settings
     * say: up and down with the left and right tap zones, left and right with
     * the top and bottom ones. One along the way the pages move turns or
     * scrolls them instead.
     */
    private fun swiped(delta: Offset, min: Float) {
        val vertical = AppSettings.tapZones == com.atlanyuey.aenht.core.TapZones.LeftRight
        val mode = AppSettings.readMode
        val pagesMoveVertically = mode != ReadMode.LeftToRight && mode != ReadMode.RightToLeft
        if (vertical == pagesMoveVertically) return
        val gesture = if (vertical) {
            if (kotlin.math.abs(delta.y) < min || kotlin.math.abs(delta.y) < 2.5f * kotlin.math.abs(delta.x)) return
            if (delta.y > 0) AppSettings.swipeDown else AppSettings.swipeUp
        } else {
            if (kotlin.math.abs(delta.x) < min || kotlin.math.abs(delta.x) < 2.5f * kotlin.math.abs(delta.y)) return
            if (delta.x > 0) AppSettings.swipeRight else AppSettings.swipeLeft
        }
        gestureAction?.invoke(gesture)
    }

    @Composable
    override fun Content() {
        // The reader is always dark, like the iOS one.
        CompositionLocalProvider(LocalColors provides DarkColors, LocalIsDark provides true) {
            ReaderBody()
        }
    }

    @Composable
    private fun ReaderBody() {
        val view = LocalView.current
        val context = LocalContext.current
        val mode = AppSettings.readMode
        val backdrop = rememberLayerBackdrop()
        uiScope = rememberCoroutineScope()

        // Immersive mode while the controls are hidden; screen on.
        DisposableEffect(chromeVisible) {
            val window = (context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
                else controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {}
        }
        run {
            val navigator = LocalNavigator.current
            val gestureOverlays = LocalOverlays.current
            val pageTitle = tr("Page Number")
            val goLabel = tr("Go")
            val cancelLabel = tr("Cancel")
            gestureAction = { gesture ->
                when (gesture) {
                    com.atlanyuey.aenht.core.ReaderGesture.None -> {}
                    com.atlanyuey.aenht.core.ReaderGesture.Exit -> navigator.pop()
                    com.atlanyuey.aenht.core.ReaderGesture.Controls -> chromeVisible = !chromeVisible
                    com.atlanyuey.aenht.core.ReaderGesture.AutoRead -> autoRead = !autoRead
                    com.atlanyuey.aenht.core.ReaderGesture.GoToPage ->
                        gestureOverlays.prompt(pageTitle, "1 – $count", "${page + 1}", goLabel, cancelLabel, number = true) { text ->
                            val target = (text.toIntOrNull() ?: return@prompt) - 1
                            if (target in 0 until count) {
                                page = target
                                uiScope.launch { if (AppSettings.readMode.isPaged) pager.scrollToPage(slot(target)) else list.scrollToItem(target) }
                            }
                        }
                }
            }
        }
        DisposableEffect(Unit) {
            ReaderKeys.handler = { forward ->
                lastTouch = android.os.SystemClock.uptimeMillis()
                val base = if (AppSettings.readMode.isPaged) turnBase else page
                go(step(base, forward))
            }
            onDispose { ReaderKeys.handler = null }
        }
        // Keep Screen On: held on until that long after the last touch, then
        // left to the system's own screen timeout (never turned off sooner).
        LaunchedEffect(AppSettings.screenOn) {
            val setting = AppSettings.screenOn
            lastTouch = android.os.SystemClock.uptimeMillis()
            if (setting.minutes == 0) {
                view.keepScreenOn = setting == com.atlanyuey.aenht.core.ScreenOn.Always
                return@LaunchedEffect
            }
            val limit = setting.minutes * 60_000L
            while (true) {
                val idle = android.os.SystemClock.uptimeMillis() - lastTouch
                view.keepScreenOn = idle < limit
                // Until the time is up; after it, a touch soon turns it back on.
                kotlinx.coroutines.delay(if (idle < limit) limit - idle else 1_000L)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                view.keepScreenOn = false
                (context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
                }
                record()
            }
        }
        // Saved once the page settles, not on every turn.
        LaunchedEffect(page) {
            delay(700)
            record()
        }
        // Double page: in the left-to-right / right-to-left modes, always or in landscape.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val landscape = configuration.screenWidthDp > configuration.screenHeightDp
        val double = (mode == ReadMode.LeftToRight || mode == ReadMode.RightToLeft) &&
            (AppSettings.doublePage == DoublePage.Always || (AppSettings.doublePage == DoublePage.Landscape && landscape))
        val cover = AppSettings.doublePageCover
        val singleStep = AppSettings.doublePageSingleStep
        val newSpreads = remember(double, cover, singleStep, count) {
            if (double) {
                Spreads(count, cover, source.pages.withIndex().filter { it.value.aspectRatio > 1.05f }.map { it.index }.toSet(), singleStep)
            } else {
                null
            }
        }
        SideEffect { if (spreads !== newSpreads) spreads = newSpreads }
        // Follow the pager / list to the page shown.
        LaunchedEffect(mode, newSpreads) {
            if (mode.isPaged) {
                if (spreads !== newSpreads) spreads = newSpreads
                if (pager.currentPage != slot(page)) pager.scrollToPage(slot(page))
                snapshotFlow { pager.currentPage }.collect { if (slot(page) != it) page = pageOfSlot(it) }
            } else {
                list.scrollToItem(page)
                snapshotFlow { list.firstVisibleItemIndex }.collect { page = it }
            }
        }
        LaunchedEffect(page) { prefetch(context, page) }
        LaunchedEffect(page) { com.atlanyuey.aenht.ui.components.ReaderThumbs.setPage(source.thumbsKey, page) }
        // Dragging the pages hides the controls…
        LaunchedEffect(mode) {
            val interactions = if (mode.isPaged) pager.interactionSource else list.interactionSource
            interactions.interactions.collect { if (it is DragInteraction.Start) chromeVisible = false }
        }
        // …and so does leaving them alone for a while (Reading settings, 3 s
        // at least; not while a finger is down or a menu or sheet is open).
        val overlays = LocalOverlays.current
        LaunchedEffect(chromeVisible, chromeActivity, touching, overlays.items.size) {
            if (!chromeVisible || touching || overlays.items.isNotEmpty()) return@LaunchedEffect
            delay((AppSettings.readerControlsDelay * 1000).toLong())
            chromeVisible = false
        }
        LaunchedEffect(autoRead) {
            if (!autoRead) return@LaunchedEffect
            chromeVisible = false
            while (true) {
                delay((AppSettings.autoReadInterval * 1000).toLong())
                val next = if (AppSettings.readMode.isPaged) step(page, true) else page + 1
                if (next !in 0 until count) {
                    autoRead = false
                    break
                }
                go(next)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            touching = event.changes.any { it.pressed }
                            if (event.type == PointerEventType.Press) {
                                chromeActivity++
                                lastTouch = android.os.SystemClock.uptimeMillis()
                            }
                        }
                    }
                },
        ) {
            val effect = pageColorEffect(AppSettings.readerGrayscale, AppSettings.readerInvert)
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.Black)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { renderEffect = effect }
                        .then(if (com.atlanyuey.aenht.core.Privacy.blursImages) Modifier.blur(40.dp) else Modifier),
                ) {
                    if (mode.isPaged) Paged(mode) else Continuous(mode)
                }
                // The reader's own brightness: a dark layer over the pages.
                val dim = (1.0 - AppSettings.readerBrightness).toFloat().coerceIn(0f, 0.8f)
                if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            }
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                AnimatedVisibility(chromeVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                    TopBar()
                }
                AnimatedVisibility(
                    chromeVisible,
                    Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                ) {
                    BottomPanel()
                }
            }
        }
    }

    // MARK: Pages

    private var pendingTap: Job? = null
    private var lastTapTime = 0L
    private var lastTapPosition = Offset.Zero

    /** Taps seen before the image handles them (no wait for a double tap at the edges). */
    private fun Modifier.readerTaps(zoomed: () -> Boolean, paged: Boolean): Modifier = pointerInput(paged) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
            val delta = up.position - down.position
            if (delta.getDistance() > viewConfiguration.touchSlop) {
                if (!zoomed()) {
                    // Fade on Swipe: the pages don't follow the finger; a swipe the way they go turns them.
                    if (paged && AppSettings.swipeFades && swipeTurn(delta, 40.dp.toPx())) return@awaitEachGesture
                    if (up.uptimeMillis - down.uptimeMillis < 700) swiped(delta, maxOf(150.dp.toPx(), 0.2f * size.height))
                }
                return@awaitEachGesture
            }
            if (up.uptimeMillis - down.uptimeMillis > 400) return@awaitEachGesture
            val forward = if (paged && !zoomed()) tapTurn(up.position.x / size.width, up.position.y / size.height, isRtl) else null
            if (forward != null) {
                pendingTap?.cancel()
                pendingTap = null
                turn(forward)
                return@awaitEachGesture
            }
            val now = up.uptimeMillis
            val near = (up.position - lastTapPosition).getDistance() < 60.dp.toPx()
            if (pendingTap != null && now - lastTapTime < 280 && near) {
                // A double tap: the image zooms (paged modes).
                pendingTap?.cancel()
                pendingTap = null
                return@awaitEachGesture
            }
            lastTapTime = now
            lastTapPosition = up.position
            pendingTap?.cancel()
            pendingTap = scope.launch {
                delay(if (paged) 280 else 0)
                pendingTap = null
                chromeVisible = !chromeVisible
            }
        }
    }

    @Composable
    private fun Paged(mode: ReadMode) {
        val content: @Composable (Int) -> Unit = { index -> if (spreads != null) SpreadPage(index) else ZoomPage(index) }
        val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        pagesLayer = layer
        Box(Modifier.fillMaxSize()) {
            // The pages, drawn through a layer that a fade turn can copy.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                // Fade on Swipe: the pages don't move with the finger (the swipe is the reader's).
                val drag = !AppSettings.swipeFades
                if (mode == ReadMode.VerticalPaged) {
                    VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, userScrollEnabled = drag, key = { it }) { content(it) }
                } else {
                    val direction = if (mode == ReadMode.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, userScrollEnabled = drag, key = { it }) { index ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content(index) }
                        }
                    }
                }
            }
            fadeCover?.let { cover ->
                val sweep = fadeSweep
                androidx.compose.foundation.Image(
                    cover, null,
                    if (sweep == null) {
                        Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha.value }
                    } else {
                        // The old page goes from the side the finger started on, with a soft edge.
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val length = if (sweep.vertical) size.height else size.width
                                val soft = length * 0.22f
                                // Kept: before the edge (left / up swipes) or after it (right / down ones).
                                val kept = fadeAlpha.value * (length + soft)
                                val (start, end, stops) = if (sweep.toStart) {
                                    Triple(kept - soft, kept, arrayOf(0f to Color.Black, 1f to Color.Transparent))
                                } else {
                                    Triple(length - kept, length - kept + soft, arrayOf(0f to Color.Transparent, 1f to Color.Black))
                                }
                                val brush = if (sweep.vertical) {
                                    androidx.compose.ui.graphics.Brush.verticalGradient(*stops, startY = start, endY = end)
                                } else {
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(*stops, startX = start, endX = end)
                                }
                                drawRect(brush, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                            }
                    },
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }

    /** Two pages side by side, zoomed together; the seam in the middle. */
    @Composable
    private fun SpreadPage(slot: Int) {
        val pages = spreads?.pages(slot).orEmpty()
        if (pages.size < 2) {
            ZoomPage(pages.firstOrNull() ?: slot)
            return
        }
        val zoomState = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
        val ordered = if (isRtl) pages.reversed() else pages
        Box(
            Modifier
                .fillMaxSize()
                .readerTaps(zoomed = { (zoomState.zoomFraction ?: 0f) > 0.01f }, paged = true)
                .zoomable(zoomState),
        ) {
            // The gap between the pages (Double Page › Gap Between Pages).
            val gap = if (AppSettings.spreadGapOn) AppSettings.spreadGap.dp else 0.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ordered.forEachIndexed { i, p ->
                    val align = if (i == 0) Alignment.CenterEnd else Alignment.CenterStart
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = align) {
                        val context = LocalContext.current
                        val crop = AppSettings.autoCrop
                        // Each half loads (and is retried) on its own: the other one stays.
                        var attempt by remember(p) { mutableIntStateOf(0) }
                        var failed by remember(p, attempt) { mutableStateOf(false) }
                        // A new image node per attempt: Coil takes an equal request for the same
                        // one (a failed one stays failed), and only this half loads again.
                        androidx.compose.runtime.key(attempt) {
                            coil3.compose.AsyncImage(
                                model = remember(p, crop) { pageRequest(context, p, crop) },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                alignment = align,
                                modifier = Modifier.fillMaxSize(),
                                onState = { failed = it is coil3.compose.AsyncImagePainter.State.Error },
                            )
                        }
                        if (failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PageRetry { attempt++ } }
                    }
                }
            }
        }
    }

    @Composable
    private fun ZoomPage(index: Int) {
        val zoomState = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
        val imageState = rememberZoomableImageState(zoomState)
        Box(
            Modifier
                .fillMaxSize()
                .readerTaps(zoomed = { (zoomState.zoomFraction ?: 0f) > 0.01f }, paged = true),
            contentAlignment = Alignment.Center,
        ) {
            val context = LocalContext.current
            val crop = AppSettings.autoCrop
            var attempt by remember(index) { mutableIntStateOf(0) }
            // One state for every attempt: the request (made once) reports into it.
            var failed by remember(index) { mutableStateOf(false) }
            val model = remember(index, crop) { pageRequest(context, index, crop) { failed = true } }
            // Super resolution: the page sharpened to the screen's resolution
            // replaces it once ready (the original stays as the placeholder,
            // and fades into the sharper one: no reload, no flash).
            val srMode = AppSettings.superResolution
            val srModel = if (AppSettings.superResolutionModel == com.atlanyuey.aenht.core.SrModel.High &&
                com.atlanyuey.aenht.core.SrModels.isReady(context)) com.atlanyuey.aenht.core.SrModel.High else com.atlanyuey.aenht.core.SrModel.Standard
            val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
            var sharp by remember(index, crop) { mutableStateOf<coil3.request.ImageRequest?>(null) }
            // Slower devices: only the page shown (measured, see SuperResolution.isFast).
            val wanted = index == page || com.atlanyuey.aenht.core.SuperResolution.isFast
            LaunchedEffect(index, crop, srMode, srModel, wanted) {
                if (srMode == com.atlanyuey.aenht.core.SrMode.Off || !wanted) return@LaunchedEffect
                val url = source.pages[index].url ?: return@LaunchedEffect
                val request = coil3.request.ImageRequest.Builder(context).data(url).allowHardware(false).apply {
                    if (crop) transformations(com.atlanyuey.aenht.core.AutoCropTransformation())
                }.build()
                val original = (coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult)
                    ?.image?.toBitmap() ?: return@LaunchedEffect
                val enhanced = com.atlanyuey.aenht.core.SuperResolution.enhance(
                    context, original, "$url|$crop", screen.width, screen.height, srMode, srModel,
                ) ?: return@LaunchedEffect
                // Not while zoomed in (the page would move under the fingers).
                androidx.compose.runtime.snapshotFlow { (zoomState.zoomFraction ?: 0f) < 0.01f }.first { it }
                sharp = coil3.request.ImageRequest.Builder(context).data(enhanced)
                    .placeholder(original.asImage())
                    .crossfade(350)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                    .build()
            }
            // A new image node per attempt (see SpreadPage): the zoom stays.
            androidx.compose.runtime.key(attempt) {
                ZoomableAsyncImage(
                    model = sharp ?: model,
                    contentDescription = null,
                    state = imageState,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            when {
                failed -> PageRetry { failed = false; attempt++ }
                !imageState.isImageDisplayed -> ActivityIndicator(size = 30.dp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }

    /**
     * The request of page [index] (cropped when [crop]); [onError] hears when
     * it fails. Each call makes a new request, so a retry loads it again.
     */
    private fun pageRequest(context: android.content.Context, index: Int, crop: Boolean, onError: () -> Unit = {}) =
        coil3.request.ImageRequest.Builder(context).data(source.pages[index].url).apply {
            if (crop) transformations(com.atlanyuey.aenht.core.AutoCropTransformation())
        }.listener(onError = { _, _ -> onError() }).build()

    /** A page that couldn't be loaded: loads it again (the page and the place stay). */
    @Composable
    private fun PageRetry(onRetry: () -> Unit) {
        com.atlanyuey.aenht.core.RetryWhenOnline(onRetry)
        com.atlanyuey.aenht.ui.components.GlassCapsuleButton(onRetry, height = 36.dp) {
            Txt(tr("Retry"), style = Type.subheadline, color = Color.White)
        }
    }

    @Composable
    private fun Continuous(mode: ReadMode) {
        val gap = if (mode == ReadMode.VerticalGap) AppSettings.pageGap.dp else 0.dp
        LazyColumn(
            Modifier.fillMaxSize().readerTaps(zoomed = { false }, paged = false),
            state = list,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(source.pages, key = { it.index }) { readerPage ->
                val ratio = (readerPage.url as? com.atlanyuey.aenht.core.EhPageRef)
                    ?.let { com.atlanyuey.aenht.core.EhPages.ratios["${it.gid}-${it.page}"] } ?: readerPage.aspectRatio
                Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.2f, 5f)), contentAlignment = Alignment.Center) {
                    var attempt by remember(readerPage.index) { mutableIntStateOf(0) }
                    var state by remember(readerPage.index, attempt) { mutableStateOf<coil3.compose.AsyncImagePainter.State?>(null) }
                    // A new image node per attempt (see SpreadPage).
                    androidx.compose.runtime.key(attempt) {
                        coil3.compose.AsyncImage(
                            model = readerPage.url,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            onState = { state = it },
                        )
                    }
                    when (state) {
                        is coil3.compose.AsyncImagePainter.State.Error -> PageRetry { attempt++ }
                        is coil3.compose.AsyncImagePainter.State.Success -> {}
                        else -> ActivityIndicator(color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    /** Pages being loaded ahead, by index. */
    private val prefetching = mutableMapOf<Int, coil3.request.Disposable>()

    /**
     * Loads the pages around [index] ahead. Pages left behind by fast turning
     * are dropped: they would hold up the one being read (E-Hentai answers a
     * few page requests a second).
     */
    private fun prefetch(context: android.content.Context, index: Int) {
        val loader = SingletonImageLoader.get(context)
        val upper = minOf(count, index + 1 + AppSettings.preloadPages)
        val lower = maxOf(0, index - 2)
        val wanted = lower until upper
        prefetching.keys.filter { it !in wanted }.forEach { prefetching.remove(it)?.dispose() }
        // The nearest first.
        for (i in wanted.sortedBy { kotlin.math.abs(it - index) }) {
            if (prefetching[i]?.isDisposed == false) continue
            val data = source.pages[i].url ?: continue
            if (data !is String && data !is com.atlanyuey.aenht.core.EhPageRef) continue
            prefetching[i] = loader.enqueue(ImageRequest.Builder(context).data(data).build())
        }
    }

    // MARK: Chrome

    @Composable
    private fun TopBar() {
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        val settingsTitle = tr("Reader Settings")
        val shareTitle = tr("Share")
        val goTitle = tr("Go to Page…")
        val pageTitle = tr("Page Number")
        val go = tr("Go")
        val cancel = tr("Cancel")
        val saveTitle = tr("Save Page")
        val sharePageTitle = tr("Share Page")
        val saved = tr("Saved to Photos.")
        val failed = tr("Couldn't Save")
        val needsAndroid10 = tr("Saving pages needs Android 10 or later. Share the page instead.")
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton()
            Column(
                Modifier
                    .weight(1f)
                    .glass(RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(source.title, style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, align = TextAlign.Center)
                Txt("${page + 1} / $count", style = Type.caption1, color = AppTheme.colors.secondaryLabel, maxLines = 1)
            }
            MenuButton(Icons.Ellipsis, tr("Options"), {
                listOf(
                    ReadMode.entries.map { mode ->
                        MenuItem(L10n.string(mode.title), mode.icon, checked = AppSettings.readMode == mode) { AppSettings.readMode = mode }
                    },
                    listOf(
                        MenuItem(goTitle, Icons.ArrowForward) {
                            overlays.prompt(pageTitle, "1 – $count", "${page + 1}", go, cancel, number = true) { text ->
                                val target = (text.toIntOrNull() ?: return@prompt) - 1
                                if (target in 0 until count) {
                                    page = target
                                    uiScope.launch { if (AppSettings.readMode.isPaged) pager.scrollToPage(slot(target)) else list.scrollToItem(target) }
                                }
                            }
                        },
                        MenuItem(saveTitle, Icons.Download) {
                            if (android.os.Build.VERSION.SDK_INT < 29) {
                                overlays.alert(failed, needsAndroid10)
                            } else {
                                uiScope.launch {
                                    val ok = runCatching { savePage(context) }.getOrDefault(false)
                                    overlays.alert(if (ok) saveTitle else failed, if (ok) saved else null)
                                }
                            }
                        },
                        MenuItem(sharePageTitle, Icons.Photo) { uiScope.launch { runCatching { sharePage(context) } } },
                    ),
                    listOf(
                        MenuItem(settingsTitle, Icons.Sliders) { openSettings(overlays) },
                        MenuItem(shareTitle, Icons.Share) { context.shareLink(source.shareUrl, source.title) },
                    ),
                )
            })
        }
    }

    /** The page shown, as decoded (from the image cache when it's there). */
    private suspend fun pageBitmap(context: android.content.Context): android.graphics.Bitmap? {
        val request = coil3.request.ImageRequest.Builder(context).data(source.pages[page].url).allowHardware(false).build()
        val result = coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult ?: return null
        return result.image.toBitmap()
    }

    /** Saves the page to Pictures/AENHT (Android 10 and later need no permission). */
    @androidx.annotation.RequiresApi(29)
    private suspend fun savePage(context: android.content.Context): Boolean {
        val bitmap = pageBitmap(context) ?: return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${source.id}-${page + 1}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AENHT")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            resolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } ?: false
        }
    }

    private suspend fun sharePage(context: android.content.Context) {
        val bitmap = pageBitmap(context) ?: return
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            java.io.File(dir, "page-${page + 1}.jpg").also { f -> f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, null))
    }

    private fun openSettings(overlays: com.atlanyuey.aenht.ui.components.OverlayController) {
        overlays.sheet { dismiss ->
            CompositionLocalProvider(LocalColors provides DarkColors, LocalIsDark provides true) {
                Column(Modifier.fillMaxWidth().background(AppTheme.colors.groupedBackground)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(tr("Reader Settings"), Modifier.weight(1f), style = Type.headline)
                        TextButton(tr("Done"), dismiss, bold = true)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        readingSettings()
                    }
                }
            }
        }
    }

    @Composable
    private fun BottomPanel() {
        val overlays = LocalOverlays.current
        var slider by remember(page) { mutableStateOf(page.toFloat()) }
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp), GlassStyle.Panel)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt("${page + 1}", style = Type.footnote, maxLines = 1)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Box(Modifier.weight(1f)) {
                            GlassSlider(
                                value = slider,
                                onChange = { value ->
                                    slider = value
                                    val target = value.toInt().coerceIn(0, count - 1)
                                    if (target != page) {
                                        page = target
                                        uiScope.launch {
                                            if (AppSettings.readMode.isPaged) pager.scrollToPage(slot(target)) else list.scrollToItem(target)
                                        }
                                    }
                                },
                                range = 0f..maxOf(1f, (count - 1).toFloat()),
                                background = Color(0xFF2C2C2E),
                            )
                        }
                    }
                    Txt("$count", style = Type.footnote, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PanelButton(Icons.ChevronBack, null) { go(step(page, forward = isRtl)) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PanelButton(if (autoRead) Icons.Pause else Icons.Play, if (autoRead) tr("Stop Auto Read") else tr("Auto Read")) {
                            autoRead = !autoRead
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PanelButton(Icons.Sliders, null) { openSettings(overlays) }
                    }
                    PanelButton(Icons.ChevronForward, null) { go(step(page, forward = !isRtl)) }
                }
            }
        }
    }

    @Composable
    private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, onClick: () -> Unit) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(icon, label, size = 20.dp)
            label?.let { Txt(it, style = Type.body.copy(fontWeight = FontWeight.Medium), maxLines = 1) }
        }
    }
}

/** Which tab of the reading settings shows (Reading, Display, Controls), as in NextN. */
var readingSettingsTab by androidx.compose.runtime.mutableIntStateOf(0)

/** Three tabs in one capsule (iOS segmented control). */
@Composable
fun SegmentedTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.tertiaryFill)
            .padding(2.dp),
    ) {
        titles.forEachIndexed { i, title ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (i == selected) colors.secondaryGroupedBackground else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(title, style = Type.footnote.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
            }
        }
    }
}

/** Shared by the reader sheet and More > Reading: three tabs, like NextN. */
fun androidx.compose.foundation.lazy.LazyListScope.readingSettings() {
    item(key = "readingTabs") {
        SegmentedTabs(listOf(tr("Reading"), tr("Display"), tr("Controls")), readingSettingsTab) { readingSettingsTab = it }
    }
    when (readingSettingsTab) {
        0 -> readingTab()
        1 -> displayTab()
        else -> controlsTab()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.readingTab() {
    section(footer = if (AppSettings.readMode.isPaged) ({ FooterText(tr("With Fade on Swipe, the pages don't move with your finger: a swipe turns to the next or previous page, and the new one fades in, sweeping the way you swiped.")) }) else null) {
        PickerRow(
            tr("Reading Direction"), ReadMode.entries, AppSettings.readMode,
            label = { tr(it.title) }, onSelect = { AppSettings.readMode = it },
            last = !(AppSettings.readMode == ReadMode.LeftToRight || AppSettings.readMode == ReadMode.RightToLeft) && !AppSettings.readMode.isPaged && AppSettings.readMode != ReadMode.VerticalGap,
        )
        // How a swipe turns the pages (not the taps: those are in Controls).
        if (AppSettings.readMode.isPaged) {
            ToggleRow(tr("Fade on Swipe"), AppSettings.swipeFades, { AppSettings.swipeFades = it })
        }
        if (AppSettings.readMode == ReadMode.LeftToRight || AppSettings.readMode == ReadMode.RightToLeft) {
            PickerRow(
                tr("Double Page"), DoublePage.entries, AppSettings.doublePage,
                label = { tr(it.title) }, onSelect = { AppSettings.doublePage = it },
            )
            if (AppSettings.doublePage != DoublePage.Off) {
                ToggleRow(tr("Cover Page Alone"), AppSettings.doublePageCover, { AppSettings.doublePageCover = it })
                ToggleRow(tr("Turn One Page at a Time"), AppSettings.doublePageSingleStep, { AppSettings.doublePageSingleStep = it })
                ToggleRow(tr("Gap Between Pages"), AppSettings.spreadGapOn, { AppSettings.spreadGapOn = it })
                if (AppSettings.spreadGapOn) {
                    // Like the gap of the continuous mode.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row {
                            Txt(tr("Page Gap"), Modifier.weight(1f))
                            Txt("${AppSettings.spreadGap.toInt()} pt", color = AppTheme.colors.secondaryLabel)
                        }
                        GlassSlider(
                            AppSettings.spreadGap.toFloat(),
                            { AppSettings.spreadGap = ((it / 2).toInt() * 2).toDouble().coerceIn(2.0, 60.0) },
                            2f..60f,
                        )
                    }
                    RowSeparator()
                }
            }
        }
        if (AppSettings.readMode.isPaged) {
            ToggleRow(tr("Auto Crop Margins"), AppSettings.autoCrop, { AppSettings.autoCrop = it }, last = true)
        }
        if (AppSettings.readMode == ReadMode.VerticalGap) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row {
                    Txt(tr("Page Gap"), Modifier.weight(1f))
                    Txt("${AppSettings.pageGap.toInt()} pt", color = AppTheme.colors.secondaryLabel)
                }
                GlassSlider(
                    AppSettings.pageGap.toFloat(),
                    { AppSettings.pageGap = ((it / 2).toInt() * 2).toDouble().coerceIn(2.0, 60.0) },
                    2f..60f,
                )
            }
        }
    }
    section(footer = { FooterText(tr("Pages ahead of the current one are downloaded in advance.")) }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                Txt(tr("Auto Read Interval"), Modifier.weight(1f))
                Txt(String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", AppSettings.autoReadInterval) + tr(" s"), color = AppTheme.colors.secondaryLabel)
            }
            GlassSlider(
                AppSettings.autoReadInterval.toFloat(),
                { AppSettings.autoReadInterval = (Math.round(it * 2) / 2.0).coerceIn(1.0, 10.0) },
                1f..10f,
            )
        }
        RowSeparator()
        ListRow(tr("Preload Pages"), value = "${AppSettings.preloadPages}", last = true) {
            Stepper(AppSettings.preloadPages, 1..10) { AppSettings.preloadPages = it }
        }
    }
}

/** Reading settings › Display › Super Resolution. */
private fun androidx.compose.foundation.lazy.LazyListScope.superResolutionSection() {
    val footer = "Sharpens low-resolution pages to your screen's resolution, on this device. A page shows at once and turns sharper when it's ready. Automatic: only pages clearly smaller than the screen; on slower devices, only the page you're on."
    section("Super Resolution", footer = {
        FooterText(tr(footer) + if (com.atlanyuey.aenht.core.SuperResolution.available) "" else "\n\n" + tr("Not available on this device."))
    }) {
        val context = LocalContext.current
        LaunchedEffect(Unit) { com.atlanyuey.aenht.core.SrModels.refresh(context) }
        val mode = AppSettings.superResolution
        PickerRow(tr("Super Resolution"), com.atlanyuey.aenht.core.SrMode.entries, mode, label = { tr(it.title) },
            onSelect = { AppSettings.superResolution = it }, last = mode == com.atlanyuey.aenht.core.SrMode.Off)
        if (mode != com.atlanyuey.aenht.core.SrMode.Off) {
            when (val state = com.atlanyuey.aenht.core.SrModels.state) {
                is com.atlanyuey.aenht.core.SrModels.State.Ready -> {
                    PickerRow(tr("Model"), com.atlanyuey.aenht.core.SrModel.entries, AppSettings.superResolutionModel,
                        label = { tr(it.title) }, onSelect = { AppSettings.superResolutionModel = it })
                    ButtonRow(tr("Delete High Quality Model"), { com.atlanyuey.aenht.core.SrModels.delete(context) }, destructive = true, last = true)
                }
                is com.atlanyuey.aenht.core.SrModels.State.Downloading ->
                    ListRow(tr("Downloading…"), value = "${(state.fraction * 100).toInt()}%", last = true,
                        onClick = { com.atlanyuey.aenht.core.SrModels.cancel() })
                else -> {
                    val failed = state as? com.atlanyuey.aenht.core.SrModels.State.Failed
                    ListRow(
                        tr("Download High Quality Model"),
                        titleColor = AppTheme.tint,
                        value = android.text.format.Formatter.formatShortFileSize(context, com.atlanyuey.aenht.core.SrModels.SIZE_BYTES),
                        subtitle = failed?.message,
                        last = true,
                        onClick = { com.atlanyuey.aenht.core.SrModels.download(context) },
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.displayTab() {
    section(footer = { FooterText(tr("Brightness, grayscale and inverted colors apply only to the pages. Grayscale and inverted colors need Android 12 or later.")) }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                Txt(tr("Page Brightness"), Modifier.weight(1f))
                Txt("${(AppSettings.readerBrightness * 100).toInt()}%", color = AppTheme.colors.secondaryLabel)
            }
            GlassSlider(
                AppSettings.readerBrightness.toFloat(),
                { AppSettings.readerBrightness = (Math.round(it * 20) / 20.0).coerceIn(0.2, 1.0) },
                0.2f..1f,
            )
        }
        RowSeparator()
        ToggleRow(tr("Grayscale"), AppSettings.readerGrayscale, { AppSettings.readerGrayscale = it })
        ToggleRow(tr("Invert Colors"), AppSettings.readerInvert, { AppSettings.readerInvert = it }, last = true)
    }
    superResolutionSection()
    section(footer = { FooterText(tr("The reader keeps the screen on this long after the last touch, then leaves it to the system's own auto-lock (it never turns the screen off sooner than the system would).")) }) {
        PickerRow(tr("Keep Screen On"), com.atlanyuey.aenht.core.ScreenOn.entries, AppSettings.screenOn,
            label = { tr(it.title) }, onSelect = { AppSettings.screenOn = it }, last = true)
    }
    section(footer = { FooterText(tr("The reader's controls hide on their own after this long without a touch.")) }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                Txt(tr("Hide Controls After"), Modifier.weight(1f))
                Txt("${AppSettings.readerControlsDelay.toInt()}" + tr(" s"), color = AppTheme.colors.secondaryLabel)
            }
            GlassSlider(
                AppSettings.readerControlsDelay.toFloat(),
                { AppSettings.readerControlsDelay = Math.round(it).toDouble().coerceIn(3.0, 10.0) },
                3f..10f,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.controlsTab() {
    section(footer = { FooterText(tr("With fading page turns, pages don't slide: the next one fades in where you are, easier on the eyes when turning quickly.")) }) {
        ToggleRow(tr("Tap to Turn Pages"), AppSettings.tapToTurnPages, { AppSettings.tapToTurnPages = it })
        PickerRow(tr("Tap Zones"), com.atlanyuey.aenht.core.TapZones.entries, AppSettings.tapZones,
            label = { tr(it.title) }, onSelect = { AppSettings.tapZones = it })
        ToggleRow(tr("Fade Page Turns"), !AppSettings.animatePageTurns, { AppSettings.animatePageTurns = !it })
        ToggleRow(tr("Volume Keys Turn Pages"), AppSettings.volumeKeyTurns, { AppSettings.volumeKeyTurns = it }, last = true)
    }
    section("Swipes", footer = { FooterText(tr("A long swipe. Not when it goes the way the pages turn or scroll (that turns them), nor while a page is zoomed in.")) }) {
        val g = com.atlanyuey.aenht.core.ReaderGesture.entries
        // Across the tap zones: up and down with left and right zones.
        if (AppSettings.tapZones == com.atlanyuey.aenht.core.TapZones.LeftRight) {
            PickerRow(tr("Swipe Down"), g, AppSettings.swipeDown, label = { tr(it.title) }, onSelect = { AppSettings.swipeDown = it })
            PickerRow(tr("Swipe Up"), g, AppSettings.swipeUp, label = { tr(it.title) }, onSelect = { AppSettings.swipeUp = it }, last = true)
        } else {
            PickerRow(tr("Swipe Right"), g, AppSettings.swipeRight, label = { tr(it.title) }, onSelect = { AppSettings.swipeRight = it })
            PickerRow(tr("Swipe Left"), g, AppSettings.swipeLeft, label = { tr(it.title) }, onSelect = { AppSettings.swipeLeft = it }, last = true)
        }
    }
}
