package com.atlanyuey.aenht.shared.reader

/** The same speed and stall limits for the native readers. Positions use each UI's own units. */
object ReaderAutoScroll {
    const val DEFAULT_SECONDS_PER_SCREEN = 10.0
    const val MIN_SECONDS_PER_SCREEN = 1.0
    const val MAX_SECONDS_PER_SCREEN = 30.0

    fun secondsPerScreen(value: Double): Double =
        if (value.isFinite()) value.coerceIn(MIN_SECONDS_PER_SCREEN, MAX_SECONDS_PER_SCREEN)
        else DEFAULT_SECONDS_PER_SCREEN

    /** Actual frame time keeps 60 Hz and 120 Hz equally fast. Never catch up after a stall. */
    fun distance(viewport: Double, secondsPerScreen: Double, elapsedSeconds: Double): Double {
        if (!viewport.isFinite() || viewport <= 0 || !elapsedSeconds.isFinite() || elapsedSeconds <= 0) return 0.0
        return viewport / secondsPerScreen(secondsPerScreen) * elapsedSeconds.coerceAtMost(0.05)
    }

    /** Stop at an unloaded image's top instead of scrolling past its placeholder. */
    fun beforeUnloadedPage(distance: Double, viewportEnd: Double, pageTop: Double): Double {
        if (!distance.isFinite() || !viewportEnd.isFinite() || !pageTop.isFinite()) return 0.0
        return distance.coerceAtLeast(0.0).coerceAtMost((pageTop - viewportEnd).coerceAtLeast(0.0))
    }
}
