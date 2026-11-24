package com.atlanyuey.aenht.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.atlanyuey.aenht.shared.reader.ReaderAutoScroll
import kotlinx.coroutines.isActive

import com.atlanyuey.aenht.ui.components.LocalNavigator
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import com.atlanyuey.aenht.ui.components.prompt
import coil3.toBitmap
import com.atlanyuey.aenht.ui.components.ButtonRow
import coil3.request.crossfade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.SingletonImageLoader
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.HistoryStore
import com.atlanyuey.aenht.core.L10n
import com.atlanyuey.aenht.core.ReadMode
import com.atlanyuey.aenht.core.SrMode
import com.atlanyuey.aenht.core.SrModel
import com.atlanyuey.aenht.core.SrModels
import com.atlanyuey.aenht.core.SuperResolution
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
import com.atlanyuey.aenht.shared.reader.Spreads
import com.atlanyuey.aenht.core.DoublePage
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.SideEffect
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.abs
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.positionChange

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
    /** A keyboard's or page turner's turn: a page, a screen in the continuous modes. */
    var deviceTurn: ((Boolean) -> Unit)? = null
    /** A stylus button: what Controls › Stylus says it does. */
    var stylus: ((com.atlanyuey.aenht.core.StylusButton) -> Unit)? = null
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

/** The continuous modes' widest pages, against the default width. */
private const val ContinuousMaxScale = 4f

/** A page's zoom against its fitted size (1: fitted). */
private val me.saket.telephoto.zoomable.ZoomableState.userZoom: Float
    get() = contentTransformation.scaleMetadata.userZoom.takeIf { it.isFinite() && it > 0f } ?: 1f

/** Zooms to [zoom] times the fitted size (zoomTo itself takes the image's own scale). */
private suspend fun me.saket.telephoto.zoomable.ZoomableState.zoomToFitted(
    zoom: Float,
    centroid: Offset = Offset.Unspecified,
    animationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
) {
    val fitted = contentTransformation.scaleMetadata.initialScale.scaleX.takeIf { it.isFinite() && it > 0f } ?: return
    if (centroid.isSpecified) zoomTo(zoom * fitted, centroid, animationSpec) else zoomTo(zoom * fitted, animationSpec = animationSpec)
}

class ReaderScreen(private val source: ReaderSource, start: Int) : Screen() {
    /** Opens out of, and closes into, the thumbnail of the page it's on (when it's on screen). */
    override fun zoomTarget() = com.atlanyuey.aenht.ui.components.ReaderThumbs.rect(source.thumbsKey, page)

    override val hidesTabBar: Boolean get() = true

    private var page by mutableIntStateOf(start.coerceIn(0, maxOf(0, source.pages.size - 1)))
    private var chromeVisible by mutableStateOf(true)
    private var autoRead by mutableStateOf(false)
    private enum class PageLoad { Loading, Ready, Failed }
    private val pageLoads = mutableStateMapOf<Int, PageLoad>()
    private val pageRatios = mutableMapOf<Int, Float>()
    private val pageRetries = mutableStateMapOf<Int, Int>()
    private var autoScrollFailedPage by mutableStateOf<Int?>(null)
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
    /** A fade turn: the page before it, going away over the new one. */
    private var fadeCover by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** How the picture of the old page goes away. */
    private var wipe by mutableStateOf<Wipe>(Wipe.Whole)
    /** How much of the old page is left (1: all of it). */
    private var wipeLeft by mutableFloatStateOf(0f)
    private var fadeJob: Job? = null

    /** The shown page's size (paged): 1 fitted, less: smaller, more: enlarged. */
    private var pageScale by mutableFloatStateOf(1f)
    /** A page made smaller makes the next ones as small (until Reset Size). */
    private var smallerCarry = 1f
    /** Counts Reset Size taps: the pages go back to their size. */
    private var sizeReset by mutableIntStateOf(0)
    /** The continuous modes' page width against the default (pinched; Keep Page Size keeps it). */
    private var stripScale by mutableFloatStateOf(
        if (AppSettings.keepContinuousPageSize) AppSettings.continuousPageScale.toFloat() else 1f,
    )

    /** The pages are bigger than usual: they move under the finger, nothing turns them, back resets them. */
    private val enlarged: Boolean get() = if (AppSettings.readMode.isPaged) pageScale > 1.01f else stripScale > 1.01f
    /** The pages aren't at their usual size: Reset Size shows. */
    private val resized: Boolean get() = abs((if (AppSettings.readMode.isPaged) pageScale else stripScale) - 1f) > 0.01f

    override val holdsBack: Boolean get() = enlarged
    override fun onBackHeld() = resetSize()

    private fun resetSize() {
        smallerCarry = 1f
        sizeReset++
        if (!AppSettings.readMode.isPaged) {
            stripFling?.cancel()
            changeStripScale(1f)
        }
    }

    private fun changeStripScale(scale: Float) {
        stripScale = scale
        if (AppSettings.keepContinuousPageSize) AppSettings.continuousPageScale = scale.toDouble()
    }

    /** The pager's page (a spread in double page) showing [page]. */
    private fun slot(page: Int) = spreads?.slotOf(page) ?: page
    private fun pageOfSlot(slot: Int) = spreads?.firstPage(slot) ?: slot

    /** The page one turn away from [base] (-1 past the ends). */
    private fun step(base: Int, forward: Boolean): Int =
        spreads?.step(base, if (forward) 1 else -1) ?: (if (forward) base + 1 else base - 1)

    private fun record() {
        HistoryStore.record(source.summary, page)
    }

    /** Turns to [target] as Tap to Turn says; a spread fade starts at [at] (fractions of the screen; the middle without). */
    private fun go(target: Int, automatic: Boolean = false, at: Offset? = null) {
        if (!automatic) autoRead = false
        if (target !in 0 until count) return
        val paged = AppSettings.readMode.isPaged
        val tapTurn = AppSettings.tapTurn
        val animate = tapTurn == com.atlanyuey.aenht.core.TapTurn.Slide
        if (paged && !animate) {
            // A turn during another one ends that one and starts its own.
            cutTurns()
            val style = if (tapTurn == com.atlanyuey.aenht.core.TapTurn.Spread) Wipe.Spread(at?.x ?: 0.5f, at?.y ?: 0.5f) else Wipe.Whole
            fadeJob = uiScope.launch { fadeTo(target, style) }
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

    /** Double tap on the title (Controls › Double Tap Top to Go to Start): the first page. */
    private fun goToStart() {
        if (!AppSettings.doubleTapTopToStart) return
        jumpToPage(0)
    }

    private fun jumpToPage(target: Int) {
        autoRead = false
        if (target !in 0 until count) return
        page = target
        uiScope.launch {
            if (AppSettings.readMode.isPaged) pager.scrollToPage(slot(target)) else list.scrollToItem(target)
        }
    }

    private fun retryContinuousPage(index: Int) {
        pageLoads[index] = PageLoad.Loading
        pageRetries[index] = (pageRetries[index] ?: 0) + 1
        if (autoScrollFailedPage == index) autoScrollFailedPage = null
    }

    /** How a turn's picture of the old page goes away. */
    private sealed interface Wipe {
        /** It fades as a whole (Page Fade). */
        data object Whole : Wipe
        /** From one side to the other with a soft edge, the way the finger goes ([toStart]: left or up). */
        data class Sweep(val vertical: Boolean, val toStart: Boolean) : Wipe
        /** In a circle growing from [x], [y] (fractions of the page) with a soft edge (Spread Fade). */
        data class Spread(val x: Float, val y: Float) : Wipe
    }

    /** Shows [target] in place: the page shown now goes away over it ([style]), with no motion. */
    private suspend fun fadeTo(target: Int, style: Wipe) {
        val cover = runCatching { pagesLayer?.toImageBitmap() }.getOrNull()
        pager.scrollToPage(slot(target))
        if (cover == null) return
        fadeCover = cover
        wipe = style
        try {
            wipeLeft = 1f
            val spec = when (style) {
                is Wipe.Spread -> androidx.compose.animation.core.tween<Float>(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.2f, 1f))
                is Wipe.Sweep -> androidx.compose.animation.core.tween(320)
                Wipe.Whole -> androidx.compose.animation.core.tween(250)
            }
            androidx.compose.animation.core.animate(1f, 0f, animationSpec = spec) { value, _ -> wipeLeft = value }
        } finally {
            if (fadeCover === cover) fadeCover = null
        }
    }

    /** A turn starting while another is drawn: that one ends at once, where it was going. */
    private fun cutTurns() {
        fadeJob?.cancel()
        fadeJob = null
        sweepBegin?.let { cancelSweep() }
        fadeCover = null
    }

    // Swipe Fade, following the finger.

    /** The turn under the finger: started (the picture taken, the next page in place) by this job. */
    private var sweepBegin: Job? = null
    private var sweepFrom = 0
    private var sweepTarget = 0

    /**
     * Starts the turn the finger began ([along]: how far it went, the way
     * the pages go): the next page goes in place under a picture of this
     * one, which the finger fades away. False at the first or last page.
     */
    private fun beginSweep(along: Float, vertical: Boolean): Boolean {
        cutTurns()
        val toStart = along < 0
        // Left (or up) is forward, except right to left.
        val forward = if (isRtl) !toStart else toStart
        val from = turnBase
        val target = step(from, forward)
        if (target !in 0 until count) return false
        autoRead = false
        chromeVisible = false
        sweepFrom = from
        sweepTarget = target
        wipe = Wipe.Sweep(vertical, toStart)
        wipeLeft = 1f
        sweepBegin = uiScope.launch {
            val cover = runCatching { pagesLayer?.toImageBitmap() }.getOrNull()
            pager.scrollToPage(slot(target))
            fadeCover = cover
        }
        return true
    }

    /** The finger is [progress] of the page's length along (0: where it started). */
    private fun moveSweep(progress: Float) {
        if (sweepBegin != null) wipeLeft = 1f - progress.coerceIn(0f, 1f)
    }

    /** A second finger, or the gesture ended elsewhere: back to the page it started from. */
    private fun cancelSweep() = endSweep(turns = false, speed = 0f, length = 1f, density = 1f)

    /**
     * The finger lifted: on to the next page ([turns]) or back, at the
     * finger's [speed] (px/s along the turn), quick but never a jump.
     */
    private fun endSweep(turns: Boolean, speed: Float, length: Float, density: Float) {
        val begin = sweepBegin ?: return
        sweepBegin = null
        val from = sweepFrom
        fadeJob = uiScope.launch {
            begin.join()
            val cover = fadeCover
            val end = if (turns) 0f else 1f
            try {
                if (cover != null) {
                    val remaining = abs(end - wipeLeft) * length
                    val millis = (remaining / maxOf(abs(speed), 1400f * density) * 1000f).coerceIn(120f, 300f).toInt()
                    androidx.compose.animation.core.animate(
                        wipeLeft, end,
                        animationSpec = androidx.compose.animation.core.tween(millis, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                    ) { value, _ -> wipeLeft = value }
                }
            } finally {
                if (!turns) {
                    // Back: the old page goes in place again under the (whole) picture of it.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pager.scrollToPage(slot(from)) }
                    page = from
                }
                if (fadeCover === cover) fadeCover = null
            }
        }
    }

    /** A drag the way the pages go (rather than across them). */
    private fun alongPages(delta: Offset): Boolean {
        val vertical = AppSettings.readMode == ReadMode.VerticalPaged
        val along = if (vertical) delta.y else delta.x
        val across = if (vertical) delta.x else delta.y
        return abs(along) >= 1.5f * abs(across)
    }

    /** The page a turn starts from (the target while a turn is running). */
    private val turnBase: Int get() = when {
        fadeJob?.isActive == true -> page
        pager.isScrollInProgress -> pageOfSlot(pager.targetPage)
        else -> if (slot(page) == pager.currentPage) page else pageOfSlot(pager.currentPage)
    }

    private fun turn(forward: Boolean, at: Offset? = null) {
        // Turning pages from the edges hides the controls.
        chromeVisible = false
        val base = if (AppSettings.readMode.isPaged) turnBase else page
        go(step(base, forward), at = at)
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
        val density by rememberUpdatedState(LocalDensity.current.density)
        val mode = AppSettings.readMode
        var active by remember { mutableStateOf(true) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { active = true }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { autoRead = false; active = false }
        val imageOptions = ReaderImageOptions(
            viewport = LocalWindowInfo.current.containerSize,
            gapPixels = with(LocalDensity.current) { 6.dp.roundToPx() },
            continuous = !mode.isPaged,
            crop = mode.isPaged && AppSettings.autoCrop,
            mode = AppSettings.superResolution,
            model = if (AppSettings.superResolutionModel == SrModel.High && SrModels.isReady(context)) SrModel.High else SrModel.Standard,
        )
        LaunchedEffect(mode) { autoRead = false }
        val backdrop = rememberLayerBackdrop()
        uiScope = com.atlanyuey.aenht.core.rememberSafeScope()

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
                                jumpToPage(target)
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
            ReaderKeys.deviceTurn = { forward ->
                lastTouch = android.os.SystemClock.uptimeMillis()
                if (AppSettings.readMode.isPaged) {
                    // Not while a page is enlarged (as from the edges).
                    if (!enlarged) go(step(turnBase, forward))
                } else {
                    autoRead = false
                    // A screen at a time, a little of the last one still showing.
                    uiScope.launch {
                        val info = list.layoutInfo
                        val screen = (info.viewportEndOffset - info.viewportStartOffset) * 0.9f
                        list.animateScrollBy(if (forward) screen else -screen)
                    }
                }
            }
            ReaderKeys.stylus = { button ->
                val action = when (button) {
                    com.atlanyuey.aenht.core.StylusButton.Primary -> AppSettings.stylusPrimary
                    com.atlanyuey.aenht.core.StylusButton.Secondary -> AppSettings.stylusSecondary
                    com.atlanyuey.aenht.core.StylusButton.Tail -> AppSettings.stylusTail
                }
                when (action) {
                    com.atlanyuey.aenht.core.StylusAction.None -> {}
                    com.atlanyuey.aenht.core.StylusAction.NextPage -> ReaderKeys.deviceTurn?.invoke(true)
                    com.atlanyuey.aenht.core.StylusAction.PreviousPage -> ReaderKeys.deviceTurn?.invoke(false)
                    com.atlanyuey.aenht.core.StylusAction.Controls -> chromeVisible = !chromeVisible
                    com.atlanyuey.aenht.core.StylusAction.AutoRead -> autoRead = !autoRead
                }
            }
            onDispose { ReaderKeys.handler = null; ReaderKeys.deviceTurn = null; ReaderKeys.stylus = null }
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
                autoRead = false
                stopDiskPrefetch()
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
        val diskPages = AppSettings.preloadPages
        LaunchedEffect(page, diskPages, active) {
            if (active) prefetch(context, page, diskPages) else stopDiskPrefetch()
        }
        LaunchedEffect(mode, newSpreads, imageOptions, active) {
            if (!active) return@LaunchedEffect
            snapshotFlow {
                val tail = if (mode.isPaged) pager.currentPage else list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: page
                // Further ahead while the strip scrolls on its own.
                tail to pagesAhead(autoRead && !mode.isPaged, AppSettings.autoScrollSecondsPerScreen)
            }.collectLatest { (tail, ahead) -> prepareUpcoming(context, tail, if (mode.isPaged) newSpreads else null, imageOptions, ahead) }
        }
        LaunchedEffect(page) { com.atlanyuey.aenht.ui.components.ReaderThumbs.setPage(source.thumbsKey, page) }
        // Dragging the pages hides the controls…
        LaunchedEffect(mode) {
            val interactions = if (mode.isPaged) pager.interactionSource else list.interactionSource
            interactions.interactions.collect {
                if (it is DragInteraction.Start) {
                    if (mode.isPaged || !AppSettings.continueAutoScrollOnDrag) autoRead = false
                    chromeVisible = false
                }
            }
        }
        // …and so does leaving them alone for a while (Reading settings, 3 s
        // at least; not while a finger is down or a menu or sheet is open).
        val overlays = LocalOverlays.current
        LaunchedEffect(overlays.items.size) { if (overlays.items.isNotEmpty()) autoRead = false }
        LaunchedEffect(chromeVisible, chromeActivity, touching, overlays.items.size) {
            if (!chromeVisible || touching || overlays.items.isNotEmpty()) return@LaunchedEffect
            delay((AppSettings.readerControlsDelay * 1000).toLong())
            chromeVisible = false
        }
        LaunchedEffect(autoRead, mode) {
            if (!autoRead) return@LaunchedEffect
            if (count == 0) { autoRead = false; return@LaunchedEffect }
            chromeVisible = false
            autoScrollFailedPage = null
            try {
                if (mode.isPaged) {
                    while (isActive) {
                        delay((AppSettings.autoReadInterval * 1000).toLong())
                        val next = step(page, true)
                        if (next !in 0 until count) break
                        go(next, automatic = true)
                    }
                } else {
                    var previous = withFrameNanos { it }
                    // The usual frame interval: the median of the last ones (a missed frame doesn't
                    // change it; a screen that changes its rate does, within a few frames).
                    val intervals = ArrayDeque<Double>()
                    while (isActive) {
                        val now = withFrameNanos { it }
                        // One frame's step for each frame shown: after a missed frame the strip
                        // doesn't jump ahead to catch up (the shake on a 60 Hz screen); it falls a
                        // frame behind instead.
                        val measured = (now - previous) / 1_000_000_000.0
                        if (measured > 0) {
                            intervals.addLast(measured)
                            if (intervals.size > 15) intervals.removeFirst()
                        }
                        val usual = intervals.sorted().let { if (it.isEmpty()) measured else it[it.size / 2] }
                        val elapsed = minOf(measured, usual * 1.05)
                        previous = now
                        // The finger and inertial scrolling own the list until they finish. Keep the
                        // playback flag on when requested, but never push against a user's gesture.
                        if (touching || list.isScrollInProgress) continue
                        val layout = list.layoutInfo
                        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                        if (viewport <= 0 || layout.visibleItemsInfo.isEmpty()) continue
                        var distance = ReaderAutoScroll.distance(viewport.toDouble(), AppSettings.autoScrollSecondsPerScreen, elapsed)
                        val approaching = layout.visibleItemsInfo.filter {
                            it.offset + it.size > layout.viewportStartOffset && it.offset <= layout.viewportEndOffset + distance
                        }
                        fun ready(item: androidx.compose.foundation.lazy.LazyListItemInfo): Boolean {
                            if (pageLoads[item.index] != PageLoad.Ready) return false
                            val ratio = pageRatios[item.index] ?: return false
                            val expected = (stripWidth / ratio).coerceAtMost(50_000f * density)
                            return abs(item.size - expected) <= maxOf(2f, expected * 0.001f)
                        }
                        val failed = approaching.firstOrNull { pageLoads[it.index] == PageLoad.Failed }
                        if (failed != null) {
                            autoScrollFailedPage = failed.index
                            break
                        }
                        for (item in approaching) {
                            if (!ready(item)) {
                                distance = ReaderAutoScroll.beforeUnloadedPage(distance, layout.viewportEndOffset.toDouble(), item.offset.toDouble())
                            }
                        }
                        val last = layout.visibleItemsInfo.lastOrNull()
                        if (!list.canScrollForward && last?.index == count - 1 && ready(last)) break
                        // Only the idle list is moved. Raw delta preserves LazyListState's fractional
                        // pixels without opening/closing a scroll mutation on every display frame.
                        if (distance > 0) list.dispatchRawDelta(distance.toFloat())
                    }
                }
            } finally {
                autoRead = false
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
                    if (mode.isPaged) Paged(mode, imageOptions, active) else Continuous(mode, imageOptions, active)
                }
                // The reader's own brightness: a dark layer over the pages.
                val dim = (1.0 - AppSettings.readerBrightness).toFloat().coerceIn(0f, 0.8f)
                if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            }
            autoScrollFailedPage?.let { failed ->
                Column(
                    Modifier.align(Alignment.Center).glass(RoundedCornerShape(20.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Txt(tr("Auto Scroll Paused"), style = Type.subheadline)
                    TextButton(tr("Retry"), { retryContinuousPage(failed) })
                }
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
                    // Swipe Fade: a drag the way the pages go is the turn's (it follows the finger).
                    if (paged && AppSettings.swipeTurn == com.atlanyuey.aenht.core.SwipeTurn.Sweep && alongPages(delta)) return@awaitEachGesture
                    if (up.uptimeMillis - down.uptimeMillis < 700) swiped(delta, maxOf(150.dp.toPx(), 0.2f * size.height))
                }
                return@awaitEachGesture
            }
            if (up.uptimeMillis - down.uptimeMillis > 400) return@awaitEachGesture
            val forward = if (paged && !zoomed()) tapTurn(up.position.x / size.width, up.position.y / size.height, isRtl) else null
            if (forward != null) {
                pendingTap?.cancel()
                pendingTap = null
                turn(forward, Offset(up.position.x / size.width, up.position.y / size.height))
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

    /**
     * Swipe Fade: a one-finger drag the way the pages go fades this page
     * away under the finger, onto the next one; let go early or slowly (or
     * brought back) it comes back. Not while the page is enlarged (the
     * finger moves it then).
     */
    private fun Modifier.sweepDrag(mode: ReadMode): Modifier = pointerInput(mode) {
        val vertical = mode == ReadMode.VerticalPaged
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (AppSettings.swipeTurn != com.atlanyuey.aenht.core.SwipeTurn.Sweep || enlarged) return@awaitEachGesture
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var total = Offset.Zero
            var sweeping = false
            val length = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1) {
                    // A second finger (a pinch): back to the page.
                    if (sweeping) cancelSweep()
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    if (sweeping) {
                        val velocity = tracker.calculateVelocity()
                        val toStart = (wipe as? Wipe.Sweep)?.toStart ?: false
                        // Positive: on the way the turn goes.
                        val speed = (if (vertical) velocity.y else velocity.x).let { if (toStart) -it else it }
                        val progress = 1f - wipeLeft
                        val turns = if (speed < -250.dp.toPx()) false else progress > 0.35f || speed > 350.dp.toPx()
                        endSweep(turns, speed, length, density)
                    }
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                total += change.positionChange()
                val along = if (vertical) total.y else total.x
                val across = if (vertical) total.x else total.y
                if (!sweeping) {
                    if (enlarged) return@awaitEachGesture
                    if (abs(along) > slop && abs(along) >= 1.5f * abs(across)) {
                        if (!beginSweep(along, vertical)) return@awaitEachGesture
                        sweeping = true
                    } else if (abs(across) > slop) {
                        return@awaitEachGesture
                    }
                } else {
                    change.consume()
                    val toStart = (wipe as? Wipe.Sweep)?.toStart ?: false
                    // Forward the way the finger first went; back to the start, nothing gone.
                    moveSweep((if (toStart) -along else along) / length)
                }
            }
        }
    }

    /**
     * A page's zoom. Allow Smaller Pages lets a pinch make it smaller than
     * it fits (down to a size that still reads); shown, it reports its size,
     * comes as small as the page before when that one was made smaller, and
     * goes back to its size on Reset Size.
     */
    @Composable
    private fun rememberPageZoom(visible: Boolean, fittedWidth: androidx.compose.ui.unit.Dp): me.saket.telephoto.zoomable.ZoomableState {
        val smaller = AppSettings.allowSmallerPages
        // Never smaller than half the fitted size, nor narrower than 300 dp.
        val minimum = if (smaller && fittedWidth.value > 0) minOf(1f, maxOf(0.5f, 300f / fittedWidth.value)) else 1f
        val state = rememberZoomableState(
            ZoomSpec(
                minimum = me.saket.telephoto.zoomable.ZoomLimit(minimum, me.saket.telephoto.zoomable.OverzoomEffect.RubberBanding),
                maximum = me.saket.telephoto.zoomable.ZoomLimit(4f, me.saket.telephoto.zoomable.OverzoomEffect.RubberBanding),
            ),
        )
        val seenReset = remember { sizeReset }
        // (Fitted: resetZoom goes to the smallest size.)
        LaunchedEffect(sizeReset) { if (sizeReset != seenReset) state.zoomToFitted(1f) }
        LaunchedEffect(visible, smaller) {
            if (!visible) return@LaunchedEffect
            // Until its image is in: at its size (not the size of the page before).
            pageScale = state.userZoom
            snapshotFlow { state.contentTransformation.isSpecified }.first { it }
            val carry = smallerCarry
            if (smaller && carry < 0.99f && abs(state.userZoom - 1f) < 0.01f) {
                state.zoomToFitted(carry, animationSpec = androidx.compose.animation.core.snap())
            }
            snapshotFlow { state.userZoom }.collect { zoom ->
                pageScale = zoom
                when {
                    zoom < 0.99f -> smallerCarry = zoom
                    zoom <= 1.01f -> smallerCarry = 1f
                }
            }
        }
        return state
    }

    /** Double tap: a page at its size enlarges; one enlarged or made smaller goes back to its size. */
    private val doubleTap = object : me.saket.telephoto.zoomable.DoubleClickToZoomListener {
        override suspend fun onDoubleClick(state: me.saket.telephoto.zoomable.ZoomableState, centroid: Offset) {
            if (abs(state.userZoom - 1f) > 0.01f) state.zoomToFitted(1f, centroid) else state.zoomToFitted(4f, centroid)
        }
    }

    @Composable
    private fun Paged(mode: ReadMode, options: ReaderImageOptions, active: Boolean) {
        val content: @Composable (Int) -> Unit = { index ->
            val visible = active && pager.currentPage == index
            if (spreads != null) SpreadPage(index, options, visible) else ZoomPage(index, options, visible)
        }
        val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        pagesLayer = layer
        Box(Modifier.fillMaxSize().sweepDrag(mode)) {
            // The pages, drawn through a layer that a fade turn can copy.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                // Swipe Fade: the pages don't slide with the finger (the drag fades them);
                // enlarged, the finger moves the page and nothing turns.
                val drag = AppSettings.swipeTurn == com.atlanyuey.aenht.core.SwipeTurn.Slide && !enlarged
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
                val style = wipe
                androidx.compose.foundation.Image(
                    cover, null,
                    when (style) {
                        Wipe.Whole -> Modifier.fillMaxSize().graphicsLayer { alpha = wipeLeft }
                        // A hole growing from the tap, with a soft edge, until none of the old page is left.
                        is Wipe.Spread -> Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val center = Offset(size.width * style.x.coerceIn(0f, 1f), size.height * style.y.coerceIn(0f, 1f))
                                val reach = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                                    .maxOf { (it - center).getDistance() }
                                val soft = 0.28f
                                val radius = maxOf(1f, reach / (1f - soft))
                                val progress = 1f - wipeLeft
                                val clearTo = progress * (1f - soft)
                                val opaqueFrom = soft * 0.4f + progress * (1f - soft * 0.4f)
                                drawRect(
                                    androidx.compose.ui.graphics.Brush.radialGradient(
                                        0f to Color.Transparent, clearTo to Color.Transparent,
                                        maxOf(opaqueFrom, clearTo + 0.0001f).coerceAtMost(1f) to Color.Black, 1f to Color.Black,
                                        center = center, radius = radius,
                                    ),
                                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                                )
                            }
                        is Wipe.Sweep -> {
                        val sweep = style
                        // The old page goes from the side the finger started on, with a soft edge.
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val length = if (sweep.vertical) size.height else size.width
                                val soft = length * 0.22f
                                // Kept: before the edge (left / up swipes) or after it (right / down ones).
                                val kept = wipeLeft * (length + soft)
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
                        }
                    },
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }

    /** Two pages side by side, zoomed together; the seam in the middle. */
    @Composable
    private fun SpreadPage(slot: Int, options: ReaderImageOptions, visible: Boolean) {
        val pages = spreads?.pages(slot).orEmpty()
        if (pages.size < 2) {
            ZoomPage(pages.firstOrNull() ?: slot, options, visible)
            return
        }
        val zoomState = rememberPageZoom(visible, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp)
        val ordered = if (isRtl) pages.reversed() else pages
        Box(
            Modifier
                .fillMaxSize()
                .readerTaps(zoomed = { zoomState.userZoom > 1.01f }, paged = true)
                .zoomable(zoomState, onDoubleClick = doubleTap),
        ) {
            // A fixed double-page seam. Page Gap adjusts only continuous reading with gaps.
            val gap = 6.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ordered.forEachIndexed { i, p ->
                    val align = if (i == 0) Alignment.CenterEnd else Alignment.CenterStart
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = align) {
                        val context = LocalContext.current
                        // Each half loads (and is retried) on its own: the other one stays.
                        var attempt by remember(p) { mutableIntStateOf(0) }
                        var failed by remember(p, attempt) { mutableStateOf(false) }
                        val sharp = enhancedPage(context, p, options, 2, visible, attempt)
                        // A new image node per attempt: Coil takes an equal request for the same
                        // one (a failed one stays failed), and only this half loads again.
                        androidx.compose.runtime.key(attempt) {
                            coil3.compose.AsyncImage(
                                model = remember(p, options) { options.request(context, source.pages[p].url, 2).build() },
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                alignment = align,
                                modifier = Modifier.fillMaxSize(),
                                onState = { failed = it is coil3.compose.AsyncImagePainter.State.Error },
                            )
                        }
                        if (sharp != null && !failed) androidx.compose.foundation.Image(
                            sharp.asImageBitmap(), null, Modifier.fillMaxSize(), alignment = align, contentScale = ContentScale.Fit,
                        )
                        if (failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PageRetry { attempt++ } }
                    }
                }
            }
        }
    }

    @Composable
    private fun ZoomPage(index: Int, options: ReaderImageOptions, visible: Boolean) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val ratio = source.pages[index].aspectRatio.takeIf { it.isFinite() && it > 0 } ?: 0.7f
        // The page's width fitted on the screen.
        val fitted = minOf(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp * ratio).dp
        val zoomState = rememberPageZoom(visible, fitted)
        val imageState = rememberZoomableImageState(zoomState)
        Box(
            Modifier
                .fillMaxSize()
                .readerTaps(zoomed = { zoomState.userZoom > 1.01f }, paged = true),
            contentAlignment = Alignment.Center,
        ) {
            val context = LocalContext.current
            var attempt by remember(index) { mutableIntStateOf(0) }
            // One state for every attempt: the request (made once) reports into it.
            var failed by remember(index) { mutableStateOf(false) }
            val model = remember(index, options) {
                options.request(context, source.pages[index].url, 1).listener(onError = { _, _ -> failed = true }).build()
            }
            val sharp = enhancedPage(context, index, options, 1, visible, attempt) {
                snapshotFlow { zoomState.userZoom <= 1.01f }.first { it }
            }
            val enhanced = remember(sharp) {
                sharp?.let { ImageRequest.Builder(context).data(it).crossfade(250).memoryCachePolicy(CachePolicy.DISABLED).build() }
            }
            // A new image node per attempt (see SpreadPage): the zoom stays.
            androidx.compose.runtime.key(attempt) {
                ZoomableAsyncImage(
                    model = enhanced ?: model,
                    contentDescription = null,
                    state = imageState,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onDoubleClick = doubleTap,
                )
            }
            when {
                failed -> PageRetry { failed = false; attempt++ }
                !imageState.isImageDisplayed -> ActivityIndicator(size = 30.dp, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }

    /** A page that couldn't be loaded: loads it again (the page and the place stay). */
    @Composable
    private fun PageRetry(onRetry: () -> Unit) {
        com.atlanyuey.aenht.core.RetryWhenOnline(onRetry)
        com.atlanyuey.aenht.ui.components.GlassCapsuleButton(onRetry, height = 36.dp) {
            Txt(tr("Retry"), style = Type.subheadline, color = Color.White)
        }
    }

    // The continuous modes' strip of pages.

    /** The strip's width (px), as laid out. */
    private var stripWidth = 0f
    /** How far the strip is moved sideways from the middle (px), when it's wider than the screen. */
    private var stripShift by mutableFloatStateOf(0f)
    /** How far it can move either way (px). */
    private var stripSlack = 0f
    private var stripFling: Job? = null

    /**
     * Pinching changes the pages' width around the fingers (Keep Page Size
     * keeps it); wider than the screen, one finger moves the strip sideways
     * (with momentum) as the list scrolls it up and down, both at once.
     */
    private fun Modifier.stripGestures(base: Float, minScale: Float, viewportWidth: Float): Modifier =
        pointerInput(base, minScale, viewportWidth) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                stripFling?.cancel()
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var moved = Offset.Zero
                var panning = false
                var pinched = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        pinched = true
                        val zoom = event.calculateZoom()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (zoom != 1f && centroid.isSpecified) zoomStrip(zoom, centroid, base, minScale, viewportWidth)
                        // Two fingers move the strip too.
                        val pan = event.calculatePan()
                        stripShift = (stripShift + pan.x).coerceIn(-stripSlack, stripSlack)
                        if (pan.y != 0f) list.dispatchRawDelta(-pan.y)
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                        continue
                    }
                    // After a pinch, the finger left on the screen does nothing.
                    if (pinched) continue
                    val change = pressed.first()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.positionChange()
                    moved += delta
                    if (!panning && stripSlack > 0f && moved.getDistance() > viewConfiguration.touchSlop) panning = true
                    if (panning) stripShift = (stripShift + delta.x).coerceIn(-stripSlack, stripSlack)
                }
                if (pinched) {
                    if (AppSettings.keepContinuousPageSize) AppSettings.continuousPageScale = stripScale.toDouble()
                } else if (panning) {
                    val velocity = tracker.calculateVelocity().x
                    if (abs(velocity) > 40f) stripFling = uiScope.launch {
                        var last = 0f
                        androidx.compose.animation.core.AnimationState(0f, velocity)
                            .animateDecay(androidx.compose.animation.core.exponentialDecay<Float>()) {
                                val next = (stripShift + value - last).coerceIn(-stripSlack, stripSlack)
                                last = value
                                if (next == stripShift && abs(velocity) > 0f) cancelAnimation()
                                stripShift = next
                            }
                    }
                }
            }
        }

    /** Pinched by [zoom] around [centroid]: the pages under the fingers stay under them. */
    private fun zoomStrip(zoom: Float, centroid: Offset, base: Float, minScale: Float, viewportWidth: Float) {
        val old = stripScale.coerceIn(minScale, ContinuousMaxScale)
        val new = (old * zoom).coerceIn(minScale, ContinuousMaxScale)
        val ratio = new / old
        if (ratio == 1f) return
        stripScale = new
        // Across: the point under the fingers, from the strip's middle, scales with it.
        val fromMiddle = centroid.x - viewportWidth / 2f - stripShift
        val slack = maxOf(0f, (base * new - viewportWidth) / 2f)
        stripSlack = slack
        stripShift = (centroid.x - viewportWidth / 2f - fromMiddle * ratio).coerceIn(-slack, slack)
        // Down: the same, from the top of the first page on the screen.
        val into = list.firstVisibleItemScrollOffset + centroid.y
        list.dispatchRawDelta(into * (ratio - 1f))
    }

    @Composable
    private fun Continuous(mode: ReadMode, options: ReaderImageOptions, active: Boolean) {
        val gap = if (mode == ReadMode.VerticalGap) AppSettings.pageGap.dp else 0.dp
        val context = LocalContext.current
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportWidth = constraints.maxWidth.toFloat()
            val viewportHeight = constraints.maxHeight.toFloat()
            // By default a whole page shows: as wide as fits with its height on
            // the screen (the gallery's usual page shape), at most the screen's width.
            val typicalRatio = remember(source) {
                val ratios = source.pages.take(12).map { it.aspectRatio }.filter { it.isFinite() && it > 0f }.sorted()
                if (ratios.isEmpty()) 0.7f else ratios[ratios.size / 2]
            }
            val base = minOf(viewportWidth, viewportHeight * typicalRatio.coerceIn(0.3f, 2f)).coerceAtLeast(1f)
            // Never narrower than reads (300 dp, and 60 % of the default).
            val minScale = minOf(1f, maxOf(0.6f, with(density) { 300.dp.toPx() } / base))
            val scale = stripScale.coerceIn(minScale, ContinuousMaxScale)
            val stripPx = base * scale
            stripWidth = stripPx
            stripSlack = maxOf(0f, (stripPx - viewportWidth) / 2f)
            val width = with(density) { stripPx.toDp() }
            LaunchedEffect(sizeReset) { stripShift = 0f }
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .stripGestures(base, minScale, viewportWidth)
                    .readerTaps(zoomed = { enlarged }, paged = false),
                state = list,
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(source.pages, key = { it.index }) { readerPage ->
                    val attempt = pageRetries[readerPage.index] ?: 0
                    var state by remember(readerPage.index, attempt) { mutableStateOf<coil3.compose.AsyncImagePainter.State?>(null) }
                    var decodedRatio by remember(readerPage.index) { mutableStateOf(pageRatios[readerPage.index]) }
                    val knownRatio = (readerPage.url as? com.atlanyuey.aenht.core.EhPageRef)
                        ?.let { com.atlanyuey.aenht.core.EhPages.ratios["${it.gid}-${it.page}"] }
                    val ratio = (decodedRatio ?: knownRatio ?: readerPage.aspectRatio).takeIf { it.isFinite() && it > 0 } ?: 0.7f
                    val request = remember(readerPage.index, attempt, options) { options.request(context, readerPage.url, 1).build() }
                    val sharp = enhancedPage(context, readerPage.index, options, 1, active && readerPage.index == page, attempt)
                    DisposableEffect(readerPage.index) {
                        onDispose { pageLoads.remove(readerPage.index) }
                    }
                    // Compose's packed constraints need a finite height, even for malformed metadata.
                    Box(
                        Modifier
                            .requiredWidth(width)
                            .height((width.value / ratio).coerceAtMost(50_000f).dp)
                            .graphicsLayer { translationX = stripShift.coerceIn(-stripSlack, stripSlack) },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.runtime.key(attempt) {
                            coil3.compose.AsyncImage(
                                model = request,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                onState = {
                                    state = it
                                    pageLoads[readerPage.index] = when (it) {
                                        is coil3.compose.AsyncImagePainter.State.Success -> {
                                            val image = it.result.image
                                            if (image.width > 0 && image.height > 0) {
                                                val actualRatio = image.width.toFloat() / image.height
                                                pageRatios[readerPage.index] = actualRatio
                                                decodedRatio = actualRatio
                                            }
                                            PageLoad.Ready
                                        }
                                        is coil3.compose.AsyncImagePainter.State.Error -> PageLoad.Failed
                                        else -> PageLoad.Loading
                                    }
                                },
                            )
                        }
                        if (sharp != null) androidx.compose.foundation.Image(sharp.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        when (state) {
                            is coil3.compose.AsyncImagePainter.State.Error -> PageRetry { retryContinuousPage(readerPage.index) }
                            is coil3.compose.AsyncImagePainter.State.Success -> {}
                            else -> if (sharp == null) ActivityIndicator(color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }

    private data class PreparedPage(val index: Int, val options: ReaderImageOptions, val pagesPerTurn: Int)
    private val preparedSizes = mutableMapOf<PreparedPage, IntSize>()

    private suspend fun prepareUpcoming(context: android.content.Context, after: Int, spreads: Spreads?, options: ReaderImageOptions, turns: Int = 2) {
        val next = upcomingReaderPages(after, count, spreads, turns)
        val keep = pagesInTurn(after, count, spreads) + next
        preparedSizes.keys.retainAll(keep.map { PreparedPage(it.index, options, it.pagesPerTurn) }.toSet())
        val loader = SingletonImageLoader.get(context)
        val decoded = mutableListOf<Pair<ReaderPageInTurn, Bitmap>>()
        val canEnhance = options.mode != SrMode.Off && SuperResolution.available
        // Decode all upcoming originals before running the more expensive inference.
        for (entry in next) {
            val data = source.pages[entry.index].url ?: continue
            val request = options.request(context, data, entry.pagesPerTurn).build()
            val result = loader.execute(request) as? SuccessResult ?: continue
            val image = result.image
            if (options.continuous && image.width > 0 && image.height > 0) {
                pageRatios[entry.index] = image.width.toFloat() / image.height
            }
            if (canEnhance && options.shouldEnhance(result) && image is coil3.BitmapImage) {
                preparedSizes[PreparedPage(entry.index, options, entry.pagesPerTurn)] = IntSize(image.width, image.height)
                decoded += entry to image.bitmap
            }
        }
        for ((entry, original) in decoded) {
            val data = source.pages[entry.index].url ?: continue
            val target = options.target(entry.pagesPerTurn)
            SuperResolution.enhance(context, original, options.enhancementKey(data),
                target.width, target.height, options.mode, options.model)
        }
    }

    @Composable
    private fun enhancedPage(context: android.content.Context, index: Int, options: ReaderImageOptions,
                             pagesPerTurn: Int, visible: Boolean, attempt: Int,
                             beforeSwap: suspend () -> Unit = {}): Bitmap? {
        val data = source.pages[index].url ?: return null
        if (options.mode == SrMode.Off) return null
        val key = PreparedPage(index, options, pagesPerTurn)
        val cacheKey = options.enhancementKey(data)
        val target = options.target(pagesPerTurn)
        val cached = preparedSizes[key]?.let { size ->
            SuperResolution.cached(size.width, size.height, cacheKey, target.width, target.height, options.mode, options.model)
        }
        var enhanced by remember(key, attempt) { mutableStateOf(cached) }
        LaunchedEffect(key, attempt, visible) {
            if (!visible || enhanced != null || !SuperResolution.available) return@LaunchedEffect
            val request = options.request(context, data, pagesPerTurn).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@LaunchedEffect
            if (!options.shouldEnhance(result)) return@LaunchedEffect
            val original = result.image.toBitmap()
            val image = SuperResolution.enhance(context, original, cacheKey, target.width, target.height, options.mode, options.model)
                ?: return@LaunchedEffect
            beforeSwap()
            enhanced = image
        }
        return enhanced ?: cached
    }

    private val diskPrefetch = mutableMapOf<Int, coil3.request.Disposable>()

    private fun stopDiskPrefetch() {
        diskPrefetch.values.forEach { it.dispose() }
        diskPrefetch.clear()
    }

    /**
     * Loads the pages around [index] ahead. Pages left behind by fast turning
     * are dropped: they would hold up the one being read (E-Hentai answers a
     * few page requests a second).
     */
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    private fun prefetch(context: android.content.Context, index: Int, diskPages: Int) {
        val loader = SingletonImageLoader.get(context)
        val upper = minOf(count, index + 1 + diskPages)
        val lower = maxOf(0, index - 2)
        val wanted = (lower until upper).filter { it != index }
        diskPrefetch.keys.filter { it !in wanted }.forEach { diskPrefetch.remove(it)?.dispose() }
        // The nearest first.
        for (i in wanted.sortedBy { kotlin.math.abs(it - index) }) {
            if (diskPrefetch[i]?.isDisposed == false) continue
            val data = source.pages[i].url ?: continue
            if (data !is String && data !is com.atlanyuey.aenht.core.EhPageRef) continue
            val request = ImageRequest.Builder(context).data(data)
                .decoderFactory(BlackholeDecoder.Factory())
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            diskPrefetch[i] = loader.enqueue(request)
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
                    // A double tap on the title: the first page (Controls › Double Tap Top to Go to Start).
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { goToStart() }) }
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
                                    jumpToPage(target)
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
        autoRead = false
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
                                    autoRead = false
                                    slider = value
                                    val target = value.toInt().coerceIn(0, count - 1)
                                    if (target != page) {
                                        jumpToPage(target)
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
                        val label = if (AppSettings.readMode.isPaged) {
                            if (autoRead) tr("Stop Auto Read") else tr("Auto Read")
                        } else {
                            if (autoRead) tr("Pause Auto Scroll") else tr("Auto Scroll")
                        }
                        PanelButton(if (autoRead) Icons.Pause else Icons.Play, label) {
                            autoRead = !autoRead
                        }
                    }
                    // Pinched smaller or bigger: back to the usual size.
                    AnimatedVisibility(resized, enter = fadeIn(), exit = fadeOut()) {
                        PanelButton(Icons.Scan, null, tr("Reset Size")) { resetSize() }
                    }
                    PanelButton(Icons.Sliders, null) { openSettings(overlays) }
                    PanelButton(Icons.ChevronForward, null) { go(step(page, forward = !isRtl)) }
                }
            }
        }
    }

    @Composable
    private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, description: String? = label, onClick: () -> Unit) {
        Row(
            Modifier
                .clickable(onClickLabel = description, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(icon, description, size = 20.dp)
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
    section(footer = if (AppSettings.readMode.isPaged) ({
        FooterText(
            tr(if (AppSettings.swipeTurn == com.atlanyuey.aenht.core.SwipeTurn.Sweep)
                "Swipe Fade: the pages don't move; the page fades away with a soft edge that follows your finger. Stop and it stops; a quick swipe turns at once."
            else "Slide: the pages slide with your finger.") + "\n" +
                tr(when (AppSettings.tapTurn) {
                    com.atlanyuey.aenht.core.TapTurn.Spread -> "Spread Fade: a tap turns the page with a fade spreading from where you tapped."
                    com.atlanyuey.aenht.core.TapTurn.Fade -> "Page Fade: a tap fades the whole page into the next."
                    com.atlanyuey.aenht.core.TapTurn.Slide -> "A tap slides the next page in."
                }),
        )
    }) else null) {
        PickerRow(
            tr("Reading Direction"), ReadMode.entries, AppSettings.readMode,
            label = { tr(it.title) }, onSelect = { AppSettings.readMode = it },
            last = !(AppSettings.readMode == ReadMode.LeftToRight || AppSettings.readMode == ReadMode.RightToLeft) && !AppSettings.readMode.isPaged && AppSettings.readMode != ReadMode.VerticalGap,
        )
        // How a swipe and a tap turn the pages, chosen apart.
        if (AppSettings.readMode.isPaged) {
            PickerRow(tr("Swipe to Turn"), com.atlanyuey.aenht.core.SwipeTurn.entries, AppSettings.swipeTurn,
                label = { tr(it.title) }, onSelect = { AppSettings.swipeTurn = it })
            PickerRow(tr("Tap to Turn"), com.atlanyuey.aenht.core.TapTurn.entries, AppSettings.tapTurn,
                label = { tr(it.title) }, onSelect = { AppSettings.tapTurn = it })
        }
        if (AppSettings.readMode == ReadMode.LeftToRight || AppSettings.readMode == ReadMode.RightToLeft) {
            PickerRow(
                tr("Double Page"), DoublePage.entries, AppSettings.doublePage,
                label = { tr(it.title) }, onSelect = { AppSettings.doublePage = it },
            )
            if (AppSettings.doublePage != DoublePage.Off) {
                ToggleRow(tr("Cover Page Alone"), AppSettings.doublePageCover, { AppSettings.doublePageCover = it })
                ToggleRow(tr("Turn One Page at a Time"), AppSettings.doublePageSingleStep, { AppSettings.doublePageSingleStep = it })
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
            if (AppSettings.readMode.isPaged) {
                Row {
                    Txt(tr("Auto Read Interval"), Modifier.weight(1f))
                    Txt(String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", AppSettings.autoReadInterval) + tr(" s"), color = AppTheme.colors.secondaryLabel)
                }
                GlassSlider(
                    AppSettings.autoReadInterval.toFloat(),
                    { AppSettings.autoReadInterval = (Math.round(it * 2) / 2.0).coerceIn(1.0, 10.0) },
                    1f..10f,
                )
            } else {
                val speedLabel = tr("Auto Scroll Speed")
                val timeLabel = java.text.NumberFormat.getNumberInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                    .apply { maximumFractionDigits = 1 }.format(AppSettings.autoScrollSecondsPerScreen) + tr(" s / screen")
                val fast = AppSettings.autoScrollSecondsPerScreen <= 3
                val warning = tr("Speed may be too fast for comfortable reading")
                val warningColor = Color(0xFFFF453A)
                if (fast) {
                    Txt(speedLabel)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Txt(warning, Modifier.weight(1f), style = Type.caption1, color = warningColor)
                        Txt(timeLabel, color = warningColor)
                    }
                } else {
                    Row {
                        Txt(speedLabel, Modifier.weight(1f))
                        Txt(timeLabel, color = AppTheme.colors.secondaryLabel)
                    }
                }
                GlassSlider(
                    (31 - AppSettings.autoScrollSecondsPerScreen).toFloat(),
                    { AppSettings.autoScrollSecondsPerScreen = ReaderAutoScroll.secondsPerScreen(Math.round((31 - it) * 2) / 2.0) },
                    1f..30f,
                    modifier = Modifier.semantics { stateDescription = if (fast) "$warning, $timeLabel" else timeLabel },
                    label = speedLabel,
                )
                Row {
                    Txt(tr("Slow"), Modifier.weight(1f), style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                    Txt(tr("Fast"), style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                }
            }
        }
        RowSeparator()
        if (!AppSettings.readMode.isPaged) {
            ToggleRow(tr("Continue Auto Scroll While Dragging"), AppSettings.continueAutoScrollOnDrag, { AppSettings.continueAutoScrollOnDrag = it })
        }
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
    // A stylus: once one has been used on this device.
    if (AppSettings.stylusSeen) {
        val actions = com.atlanyuey.aenht.core.StylusAction.entries
        section(
            "Stylus",
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FooterText(tr("While reading, press a button of the stylus held over the screen; pens with Bluetooth buttons (Android 14 and later) work away from it too. Which buttons a pen has depends on the pen."))
                    if (com.atlanyuey.aenht.core.ExternalKeys.hasSPen) {
                        FooterText(tr("Galaxy S Pen: turn on AENHT in Settings › Advanced features › S Pen › Air actions. Then a press of the S Pen's button turns to the next page, a double press to the previous one, and swipes left and right in the air turn too."))
                    }
                }
            },
        ) {
            PickerRow(tr("Pen Button"), actions, AppSettings.stylusPrimary, label = { tr(it.title) }, onSelect = { AppSettings.stylusPrimary = it })
            PickerRow(tr("Second Button"), actions, AppSettings.stylusSecondary, label = { tr(it.title) }, onSelect = { AppSettings.stylusSecondary = it })
            PickerRow(tr("Tail Button"), actions, AppSettings.stylusTail, label = { tr(it.title) }, onSelect = { AppSettings.stylusTail = it }, last = true)
        }
    }
    // A keyboard or Bluetooth page turner: only while one is connected.
    val keyboards = com.atlanyuey.aenht.core.ExternalKeys.names
    if (keyboards.isNotEmpty()) {
        section(
            "Keyboard and Page Turner",
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FooterText(tr("Connected: %@", keyboards.joinToString(", ")))
                    FooterText(tr("→ ↓, Page Down and Space turn to the next page; ← ↑ and Page Up to the previous one (← → follow the reading direction). In the continuous modes they scroll a screen. Most Bluetooth page turners send these keys; ones that send the volume buttons use Volume Keys Turn Pages."))
                    FooterText(tr("A Windows pen paired over Bluetooth (such as Surface Pen): a click of its top button turns to the next page, a double click to the previous one."))
                }
            },
        ) {
            ToggleRow(tr("Keys Turn Pages"), AppSettings.pageKeys, { AppSettings.pageKeys = it }, last = !AppSettings.pageKeys)
            if (AppSettings.pageKeys) {
                ToggleRow(tr("Swap Next and Previous"), AppSettings.pageKeysSwap, { AppSettings.pageKeysSwap = it }, last = true)
            }
        }
    }
    section(footer = { FooterText(tr("Double-tap the title at the top of the reader to go back to the first page.")) }) {
        ToggleRow(tr("Double Tap Top to Go to Start"), AppSettings.doubleTapTopToStart, { AppSettings.doubleTapTopToStart = it }, last = true)
    }
    if (!AppSettings.readMode.isPaged) {
        // Taps, tap zones and swipes turn pages: the continuous modes scroll instead.
        section(footer = { FooterText(tr("Pinch with two fingers to make the pages smaller or bigger. With Keep Page Size, that size stays for every page and the next galleries; drag the black sides to move the pages. Reset Size, at the bottom of the reader, brings back the usual size.")) }) {
            ToggleRow(tr("Keep Page Size"), AppSettings.keepContinuousPageSize, { AppSettings.keepContinuousPageSize = it })
            ToggleRow(tr("Volume Keys Turn Pages"), AppSettings.volumeKeyTurns, { AppSettings.volumeKeyTurns = it }, last = true)
        }
        return
    }
    section(footer = { FooterText(tr("Pinching in can make a page smaller than the screen, with black around it. Off, a page fills the screen at least; pinching only enlarges it.")) }) {
        ToggleRow(tr("Allow Smaller Pages"), AppSettings.allowSmallerPages, { AppSettings.allowSmallerPages = it }, last = true)
    }
    section(footer = { FooterText(tr("How a tap turns the page (slide, page fade or spread fade) is set in Reading › Tap to Turn.")) }) {
        ToggleRow(tr("Tap to Turn Pages"), AppSettings.tapToTurnPages, { AppSettings.tapToTurnPages = it })
        PickerRow(tr("Tap Zones"), com.atlanyuey.aenht.core.TapZones.entries, AppSettings.tapZones,
            label = { tr(it.title) }, onSelect = { AppSettings.tapZones = it })
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
