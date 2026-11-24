package com.atlanyuey.aenht.ui.components

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
                                    // A zooming screen (the reader) grows out of and shrinks back
                                    // into its thumbnail; the one under it stays in place.
                                    val target = if (p < 1f) topScreen.zoomTarget() else null
                                    if (target != null) {
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
                                .then(if (isTop && topScreen.zoomTarget() == null) Modifier.leadingShadow { navigator.progress } else Modifier),
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
        }
    }

    val held = navigator.top.holdsBack
    PredictiveBackHandler(enabled = navigator.canPop && !held) { events ->
        navigator.beginBack()
        try {
            events.collect { event -> navigator.dragBack(event.progress) }
            navigator.finishBack()
        } catch (e: CancellationException) {
            navigator.cancelBack()
            throw e
        }
    }
    androidx.activity.compose.BackHandler(enabled = held) { navigator.top.onBackHeld() }
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
