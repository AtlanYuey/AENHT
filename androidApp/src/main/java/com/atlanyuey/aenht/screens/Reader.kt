package com.atlanyuey.aenht.screens

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
private fun turnsPage(fraction: Float) = AppSettings.tapToTurnPages && (fraction < 0.3f || fraction > 0.7f)

class ReaderScreen(private val source: ReaderSource, start: Int) : Screen() {
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

    /** Shows [target] in place: the page shown now fades out over it (no motion). */
    private suspend fun fadeTo(target: Int) {
        val cover = runCatching { pagesLayer?.toImageBitmap() }.getOrNull()
        pager.scrollToPage(slot(target))
        if (cover == null) return
        fadeCover = cover
        try {
            fadeAlpha.snapTo(1f)
            fadeAlpha.animateTo(0f, androidx.compose.animation.core.tween(250))
        } finally {
            if (fadeCover === cover) fadeCover = null
        }
    }

    /** The page a turn starts from (the target while a turn is running). */
    private val turnBase: Int get() = when {
        fadeJob?.isActive == true -> page
        pager.isScrollInProgress -> pageOfSlot(pager.targetPage)
        else -> if (slot(page) == pager.currentPage) page else pageOfSlot(pager.currentPage)
    }

    private fun turn(fraction: Float) {
        // Turning pages from the edges hides the controls.
        chromeVisible = false
        val forward = if (fraction < 0.5f) isRtl else !isRtl
        val base = if (AppSettings.readMode.isPaged) turnBase else page
        go(step(base, forward))
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
        DisposableEffect(Unit) {
            ReaderKeys.handler = { forward ->
                val base = if (AppSettings.readMode.isPaged) turnBase else page
                go(step(base, forward))
            }
            onDispose { ReaderKeys.handler = null }
        }
        DisposableEffect(Unit) {
            view.keepScreenOn = AppSettings.keepScreenOn
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
        val newSpreads = remember(double, cover, count) {
            if (double) Spreads(count, cover, source.pages.withIndex().filter { it.value.aspectRatio > 1.05f }.map { it.index }.toSet()) else null
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
                            if (event.type == PointerEventType.Press) chromeActivity++
                        }
                    }
                },
        ) {
            val effect = pageColorEffect(AppSettings.readerGrayscale, AppSettings.readerInvert)
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.Black)) {
                Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = effect }) {
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
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            if (up.uptimeMillis - down.uptimeMillis > 400) return@awaitEachGesture
            val fraction = up.position.x / size.width
            if (paged && !zoomed() && turnsPage(fraction)) {
                pendingTap?.cancel()
                pendingTap = null
                turn(fraction)
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
                if (mode == ReadMode.VerticalPaged) {
                    VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, key = { it }) { content(it) }
                } else {
                    val direction = if (mode == ReadMode.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, pageSpacing = 16.dp, key = { it }) { index ->
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content(index) }
                        }
                    }
                }
            }
            fadeCover?.let { cover ->
                androidx.compose.foundation.Image(
                    cover, null,
                    Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha.value },
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
            Row(Modifier.fillMaxSize()) {
                ordered.forEachIndexed { i, p ->
                    val align = if (i == 0) Alignment.CenterEnd else Alignment.CenterStart
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = align) {
                        val context = LocalContext.current
                        val crop = AppSettings.autoCrop
                        coil3.compose.AsyncImage(
                            model = remember(p, crop) {
                                val url = source.pages[p].url
                                if (!crop) url else coil3.request.ImageRequest.Builder(context).data(url)
                                    .transformations(com.atlanyuey.aenht.core.AutoCropTransformation()).build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = align,
                            modifier = Modifier.fillMaxSize(),
                        )
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
            val model = remember(index, crop) {
                val url = source.pages[index].url
                if (!crop) url else coil3.request.ImageRequest.Builder(context).data(url)
                    .transformations(com.atlanyuey.aenht.core.AutoCropTransformation()).build()
            }
            ZoomableAsyncImage(
                model = model,
                contentDescription = null,
                state = imageState,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (!imageState.isImageDisplayed) ActivityIndicator(size = 30.dp, color = Color.White.copy(alpha = 0.6f))
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
                    ActivityIndicator(color = Color.White.copy(alpha = 0.5f))
                    coil3.compose.AsyncImage(
                        model = readerPage.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun prefetch(context: android.content.Context, index: Int) {
        val loader = SingletonImageLoader.get(context)
        val upper = minOf(count, index + 1 + AppSettings.preloadPages)
        val lower = maxOf(0, index - 2)
        for (i in lower until upper) {
            val data = source.pages[i].url ?: continue
            if (data !is String && data !is com.atlanyuey.aenht.core.EhPageRef) continue
            loader.enqueue(ImageRequest.Builder(context).data(data).build())
        }
    }

    // MARK: Chrome

    @Composable
    private fun TopBar() {
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        val settingsTitle = tr("Reader Settings")
        val shareTitle = tr("Share")
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
                        MenuItem(settingsTitle, Icons.Sliders) { openSettings(overlays) },
                        MenuItem(shareTitle, Icons.Share) { context.shareLink(source.shareUrl, source.title) },
                    ),
                )
            })
        }
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

/** Shared by the reader sheet and More > Reading. */
fun androidx.compose.foundation.lazy.LazyListScope.readingSettings() {
    run {
        section(footer = { FooterText(tr("With fading page turns, pages don't slide: the next one fades in where you are, easier on the eyes when turning quickly.")) }) {
            PickerRow(
                tr("Reading Direction"), ReadMode.entries, AppSettings.readMode,
                label = { tr(it.title) }, onSelect = { AppSettings.readMode = it },
            )
            if (AppSettings.readMode == ReadMode.LeftToRight || AppSettings.readMode == ReadMode.RightToLeft) {
                PickerRow(
                    tr("Double Page"), DoublePage.entries, AppSettings.doublePage,
                    label = { tr(it.title) }, onSelect = { AppSettings.doublePage = it },
                )
                if (AppSettings.doublePage != DoublePage.Off) {
                    ToggleRow(tr("Cover Page Alone"), AppSettings.doublePageCover, { AppSettings.doublePageCover = it })
                }
            }
            if (AppSettings.readMode.isPaged) {
                ToggleRow(tr("Auto Crop Margins"), AppSettings.autoCrop, { AppSettings.autoCrop = it })
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
                RowSeparator()
            }
            ToggleRow(tr("Tap Edges to Turn Pages"), AppSettings.tapToTurnPages, { AppSettings.tapToTurnPages = it })
            ToggleRow(tr("Fade Page Turns"), !AppSettings.animatePageTurns, { AppSettings.animatePageTurns = !it })
            ToggleRow(tr("Volume Keys Turn Pages"), AppSettings.volumeKeyTurns, { AppSettings.volumeKeyTurns = it })
            ToggleRow(tr("Keep Screen On"), AppSettings.keepScreenOn, { AppSettings.keepScreenOn = it }, last = true)
        }
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
        section(footer = { FooterText(tr("Pages ahead of the current one are downloaded in advance.")) }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row {
                    Txt(tr("Auto Read Interval"), Modifier.weight(1f))
                    Txt(String.format(java.util.Locale.getDefault(), "%.1f", AppSettings.autoReadInterval) + tr(" s"), color = AppTheme.colors.secondaryLabel)
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
}
