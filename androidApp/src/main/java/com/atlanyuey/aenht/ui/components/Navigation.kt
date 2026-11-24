package com.atlanyuey.aenht.ui.components

import com.atlanyuey.aenht.core.privacyText
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** A screen in a navigation stack; it keeps its own state while in the stack. */
abstract class Screen {
    val id: String = UUID.randomUUID().toString()

    /**
     * Work tied to this screen, cancelled when it leaves the stack. It has
     * the UI's frame clock: animations started in it (a list scrolled to the
     * top) need one, and without it they threw and closed the app. An error
     * in this work is logged (About › Diagnostics) instead of closing it.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            androidx.compose.ui.platform.AndroidUiDispatcher.Main[androidx.compose.runtime.MonotonicFrameClock]!! +
            com.atlanyuey.aenht.core.LogErrors,
    )

    /** Hides the tab bar while this screen is on top (the reader). */
    open val hidesTabBar: Boolean get() = false

    /**
     * Where this screen zooms out of and back into (window coordinates),
     * instead of sliding: the reader and the thumbnail of its page.
     */
    open fun zoomTarget(): androidx.compose.ui.geometry.Rect? = null

    /**
     * A cover shared with the card this screen was opened from (a gallery's
     * page): the cover itself flies between them while this screen fades in
     * or out, instead of the screen sliding.
     */
    open fun sharedCover(): SharedCover? = null

    /**
     * Back doesn't leave this screen now (the reader with a page enlarged):
     * it does [onBackHeld] instead. Read in composition (snapshot state).
     */
    open val holdsBack: Boolean get() = false

    open fun onBackHeld() {}

    @Composable
    abstract fun Content()

    /** The screen's tab was selected (Favorites / History follow the home page's site). */
    open fun onAppear() {}

    open fun onRemoved() {
        scope.cancel()
    }
}

/**
 * A cover that flies from a card ([from]) to the same cover on the screen
 * opened from it ([to], once laid out), as one picture ([item]'s cover).
 */
class SharedCover(
    val key: String,
    val item: com.atlanyuey.aenht.shared.site.GallerySummary,
    val from: androidx.compose.ui.geometry.Rect,
    val to: androidx.compose.ui.geometry.Rect?,
    /** The card's title and the page's: one flies into the other (crossfading), with the cover. */
    val titleFrom: TitleSpot? = null,
    val titleTo: TitleSpot? = null,
)

/**
 * A navigation stack with iOS push / pop transitions.
 *
 * Every push and pop finishes, whatever happens during its animation: a
 * new push or pop first completes the one running (its page all the way
 * in, or gone), and so does the tab's view going away (another tab chosen),
 * which stops the animation. A stack that isn't shown changes at once.
 */
class Navigator(root: Screen) {
    val stack = mutableStateListOf(root)

    /** How far the top screen is shown (0 = off to the right, 1 = in place). */
    internal var progress by mutableFloatStateOf(1f)
        private set
    /** Screens removed whose saved state the NavHost forgets (next time it draws). */
    internal val removed = ArrayList<Screen>()
    /** The NavHost's coroutine scope (animations need its frame clock); cancelled when it goes. */
    internal var uiScope: CoroutineScope? = null
    private val spec = spring<Float>(dampingRatio = 1f, stiffness = 420f)

    /** A push or pop under way: [popped] is the screen it removes (null for a push). */
    private class Transition(val popped: Screen?) { var done = false }
    private var transition: Transition? = null
    private var job: Job? = null

    val top: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    /** The scope to animate in, if this stack is on screen. */
    private fun animationScope(): CoroutineScope? = uiScope?.takeIf { it.isActive }

    private fun remove(screen: Screen) {
        if (stack.size > 1 && stack.remove(screen)) {
            removed += screen
            screen.onRemoved()
        }
    }

    /** Ends [t] where it was going: its page in place, or removed. Once. */
    private fun complete(t: Transition) {
        if (t.done) return
        t.done = true
        t.popped?.let { remove(it) }
        if (transition === t) {
            transition = null
            progress = 1f
        }
    }

    /** Completes the push or pop under way (before another starts). */
    private fun finishNow() {
        transition?.let { complete(it) }
        job?.cancel()
        job = null
    }

    /** Runs [t]: the animation from [from] to [to], then (or when stopped) its completion. */
    private fun run(t: Transition, from: Float, to: Float) {
        val scope = animationScope()
        if (scope == null) {
            transition = t
            complete(t)
            return
        }
        transition = t
        progress = from
        job = scope.launch {
            try {
                animate(from, to, animationSpec = spec) { value, _ -> if (transition === t) progress = value }
            } finally {
                complete(t)
            }
        }
    }

    fun push(screen: Screen) {
        finishNow()
        BarsState.show()
        stack.add(screen)
        run(Transition(null), 0f, 1f)
    }

    fun pop() {
        if (!canPop) return
        finishNow()
        BarsState.show()
        run(Transition(stack.last()), progress, 0f)
    }

    /** Pops screens above the nearest one that matches [predicate] (or to the root). */
    fun popTo(predicate: (Screen) -> Boolean) {
        finishNow()
        val target = stack.indexOfLast(predicate).takeIf { it >= 0 } ?: 0
        while (stack.size > target + 2) remove(stack[stack.size - 2])
        pop()
    }

    fun popToRoot() {
        finishNow()
        while (stack.size > 2) remove(stack[stack.size - 2])
        pop()
    }

    /** Replaces the top screen without animation. */
    fun replace(screen: Screen) {
        finishNow()
        val old = stack.last()
        stack.add(screen)
        if (stack.remove(old)) {
            removed += old
            old.onRemoved()
        }
    }

    /** The back gesture starts: what was under way finishes first. */
    internal fun beginBack() = finishNow()

    /** The back gesture at [fraction] of the way. */
    internal fun dragBack(fraction: Float) {
        if (canPop) progress = 1f - fraction.coerceIn(0f, 1f)
    }

    /** The back gesture let go past the point: the top screen goes. */
    internal fun finishBack() = pop()

    /** The back gesture called off: the top screen slides back in place. */
    internal fun cancelBack() {
        finishNow()
        run(Transition(null), progress, 1f)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

/**
 * Whether this screen has come in: false while it is being pushed (its
 * animation runs), true once it's in place, and at once when it's shown
 * again (going back to it). What it loads can wait for this: added in the
 * middle of the animation, it made the animation stutter.
 */
@Composable
fun Screen.arrived(): Boolean {
    val navigator = LocalNavigator.current
    val screen = this
    var arrived by remember { mutableStateOf(!(navigator.top === screen && navigator.progress < 1f)) }
    if (!arrived) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { navigator.top !== screen || navigator.progress >= 1f }.first { it }
            arrived = true
        }
    }
    return arrived
}

/** A list item that fades in when it first shows (a gallery page's details once loaded). */
@Composable
fun androidx.compose.foundation.lazy.LazyItemScope.FadeIn(content: @Composable () -> Unit) {
    Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240), placementSpec = null, fadeOutSpec = null)) { content() }
}

/**
 * Shows the top of a navigation stack. The screen below stays visible while
 * a screen slides in or out (also during Android's predictive back gesture).
 */
@Composable
fun NavHost(navigator: Navigator, modifier: Modifier = Modifier) {
    val holder = rememberSaveableStateHolder()
    navigator.uiScope = com.atlanyuey.aenht.core.rememberSafeScope()
    if (navigator.removed.isNotEmpty()) {
        navigator.removed.forEach { holder.removeState(it.id) }
        navigator.removed.clear()
    }
    val inTransition by remember(navigator) { derivedStateOf { navigator.progress < 1f } }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        var hostOrigin by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInWindow() }) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val stack = navigator.stack
            val topScreen = stack.last()
            stack.forEachIndexed { index, screen ->
                val isTop = index == stack.lastIndex
                val isUnder = index == stack.lastIndex - 1
                if (isTop || (isUnder && inTransition)) {
                    key(screen.id) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = navigator.progress
                                    // A shared cover (a gallery's page): the cover flies (drawn below);
                                    // the page fades in around it, the screen under it stays.
                                    val shared = if (p < 1f) topScreen.sharedCover() else null
                                    // A zooming screen (the reader) grows out of and shrinks back
                                    // into its thumbnail; the one under it stays in place.
                                    val target = if (p < 1f && shared == null) topScreen.zoomTarget() else null
                                    if (shared != null) {
                                        if (isTop) alpha = ((p - 0.1f) / 0.75f).coerceIn(0f, 1f)
                                    } else if (target != null) {
                                        if (isTop) {
                                            val rect = target.translate(-hostOrigin)
                                            val scale = maxOf(rect.width / width, rect.height / height)
                                            val s = scale + (1f - scale) * p
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                            scaleX = s
                                            scaleY = s
                                            translationX = (rect.center.x - width * s / 2f) * (1f - p)
                                            translationY = (rect.center.y - height * s / 2f) * (1f - p)
                                            alpha = (p * 2.5f).coerceIn(0f, 1f)
                                        }
                                    } else {
                                        translationX = if (isTop) width * (1f - p) else -width * 0.3f * p
                                    }
                                }
                                .then(if (isTop && topScreen.zoomTarget() == null && topScreen.sharedCover() == null) Modifier.leadingShadow { navigator.progress } else Modifier),
                        ) {
                            holder.SaveableStateProvider(screen.id) { screen.Content() }
                            if (isUnder) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = 0.14f * navigator.progress }
                                        .background(Color.Black),
                                )
                            }
                        }
                    }
                }
            }
            // The shared cover, flying between the card and the page (over both).
            val flying = if (inTransition) topScreen.sharedCover() else null
            androidx.compose.runtime.SideEffect { GalleryCovers.flying = flying?.key }
            if (flying != null) {
                key(flying.key) {
                    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 13.dp.toPx() }
                    Box(
                        Modifier
                            .layout { measurable, constraints ->
                                val p = navigator.progress.coerceIn(0f, 1f)
                                val now = topScreen.sharedCover()
                                val from = (now?.from ?: flying.from).translate(-hostOrigin)
                                val to = (now?.to ?: now?.from ?: flying.from).translate(-hostOrigin)
                                val r = androidx.compose.ui.geometry.lerp(from, to, p)
                                val w = r.width.roundToInt().coerceAtLeast(1)
                                val h = r.height.roundToInt().coerceAtLeast(1)
                                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
                            }
                            .graphicsLayer {
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
                                clip = true
                            },
                    ) {
                        CoverView(flying.item, Modifier.fillMaxSize(), cornerRadius = 13.dp)
                    }
                    // The title flies too: the card's fades out as it moves and grows into the
                    // page's place; the page's fades in from the card's place, at the card's size.
                    val fromTitle = flying.titleFrom
                    val toTitle = flying.titleTo
                    if (fromTitle != null && toTitle != null) {
                        FlyingTitle(fromTitle, fromTitle, toTitle, outgoing = true, progress = { navigator.progress }, hostOrigin = { hostOrigin })
                        FlyingTitle(toTitle, fromTitle, toTitle, outgoing = false, progress = { navigator.progress }, hostOrigin = { hostOrigin })
                    }
                }
            }
        }
    }

    val held = navigator.top.holdsBack
    // An open menu, alert or sheet takes Back first (this handler, added when
    // a tab is shown, would otherwise come before the overlays' one).
    val overlayOpen = LocalOverlays.current.items.isNotEmpty()
    PredictiveBackHandler(enabled = navigator.canPop && !held && !overlayOpen) { events ->
        navigator.beginBack()
        try {
            events.collect { event -> navigator.dragBack(event.progress) }
            navigator.finishBack()
        } catch (e: CancellationException) {
            navigator.cancelBack()
            throw e
        }
    }
    androidx.activity.compose.BackHandler(enabled = held && !overlayOpen) { navigator.top.onBackHeld() }
}

/**
 * [spot]'s text, as it's drawn there (its width, style and lines), moving
 * from [from]'s place to [to]'s and scaled from one's width to the other's:
 * the card's title fading out ([outgoing]), or the page's fading in.
 */
@Composable
private fun FlyingTitle(spot: TitleSpot, from: TitleSpot, to: TitleSpot, outgoing: Boolean, progress: () -> Float, hostOrigin: () -> Offset) {
    val ownWidth = (spot.rect?.width ?: 0f).roundToInt().coerceAtLeast(1)
    Box(
        Modifier.layout { measurable, constraints ->
            val p = progress().coerceIn(0f, 1f)
            val a = from.rect ?: return@layout layout(0, 0) {}
            val b = to.rect ?: return@layout layout(0, 0) {}
            val origin = hostOrigin()
            val x = a.left + (b.left - a.left) * p - origin.x
            val y = a.top + (b.top - a.top) * p - origin.y
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints(minWidth = ownWidth, maxWidth = ownWidth))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
        },
    ) {
        Txt(
            spot.text,
            Modifier.graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                val a = from.rect?.width ?: 1f
                val b = to.rect?.width ?: 1f
                // The card's text grows to the page's width; the page's starts at the card's.
                val s = if (outgoing) 1f + (b / a - 1f) * p else a / b + (1f - a / b) * p
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = s
                scaleY = s
                alpha = if (outgoing) (1f - p * 1.6f).coerceIn(0f, 1f) else ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
            }.privacyText(),
            style = spot.style,
            maxLines = spot.maxLines,
        )
    }
}

/** The soft shadow on the leading edge of a screen sliding over another. */
private fun Modifier.leadingShadow(progress: () -> Float) = drawWithContent {
    val p = progress()
    if (p < 1f) {
        val width = 16.dp.toPx()
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.12f * (1f - p * 0.5f))),
                startX = -width,
                endX = 0f,
            ),
            topLeft = Offset(-width, 0f),
            size = Size(width, size.height),
        )
    }
    drawContent()
}
