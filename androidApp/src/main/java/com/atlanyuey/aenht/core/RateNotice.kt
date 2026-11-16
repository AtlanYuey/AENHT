package com.atlanyuey.aenht.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.shared.net.RateLimiter
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * The sites limit how many requests may be sent: when the app has to wait
 * for its turn (a few seconds or more), it says so instead of only showing
 * a spinner.
 */
object RateNotice {
    /** When the request that waits goes on (ms since the epoch); 0: none waits. */
    var until by mutableLongStateOf(0L)
        private set

    /** No request waits any more (tests start afresh). */
    fun clear() { until = 0L }

    /** Waits shorter than this aren't worth a notice. */
    private const val WORTH_SAYING = 2_000L

    fun watch(limiter: RateLimiter) {
        limiter.onWait = { _, wait ->
            if (wait >= WORTH_SAYING) until = maxOf(until, System.currentTimeMillis() + wait)
        }
    }

    /** The notice, at the top of the screen while a request waits. */
    @Composable
    fun Banner() {
        var seconds by remember { mutableLongStateOf(0L) }
        LaunchedEffect(until) {
            while (true) {
                val left = until - System.currentTimeMillis()
                seconds = if (left > 0) (left + 999) / 1000 else 0
                if (left <= 0) break
                delay(250)
            }
        }
        if (seconds <= 0) return
        Box(
            Modifier.fillMaxSize().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 52.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Txt(
                tr("The site limits requests. Continuing in %lld s.", seconds),
                Modifier
                    .background(AppTheme.colors.secondaryGroupedBackground, CircleShape)
                    .border(0.5.dp, AppTheme.colors.separator, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = Type.footnote,
            )
        }
    }
}
