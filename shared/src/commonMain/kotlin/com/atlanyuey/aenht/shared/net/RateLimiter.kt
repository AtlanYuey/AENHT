package com.atlanyuey.aenht.shared.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** At most [count] requests in any [window]. */
data class RateLimit(val count: Int, val window: Duration = 1.minutes)

/**
 * A sliding-window limiter shared by every request of a site. Requests that
 * would exceed a bucket's limit wait until a slot is free, so screens never
 * see a 429 caused by the app itself.
 *
 * One instance per site; buckets are named by the caller (usually the
 * endpoint), e.g. "search" or "gallery".
 */
class RateLimiter(private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val mutex = Mutex()
    private val history = mutableMapOf<String, ArrayDeque<Long>>()

    /** Called with the wait (ms) when a request has to wait for a slot. */
    var onWait: ((bucket: String, waitMillis: Long) -> Unit)? = null

    suspend fun acquire(bucket: String, limit: RateLimit) {
        while (true) {
            val wait = mutex.withLock {
                val t = now()
                val windowMs = limit.window.inWholeMilliseconds
                val q = history.getOrPut(bucket) { ArrayDeque() }
                while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
                if (q.size < limit.count) {
                    q.addLast(t)
                    0L
                } else {
                    windowMs - (t - q.first()) + 1
                }
            }
            if (wait <= 0) return
            onWait?.invoke(bucket, wait)
            delay(wait)
        }
    }

    /**
     * The server said "too many requests": block the bucket for [retryAfter]
     * by filling it, so the next requests wait instead of failing again.
     */
    suspend fun backOff(bucket: String, limit: RateLimit, retryAfter: Duration) {
        mutex.withLock {
            val t = now()
            val windowMs = limit.window.inWholeMilliseconds
            // Entries that expire exactly retryAfter from now.
            val stamp = t - windowMs + retryAfter.inWholeMilliseconds
            history[bucket] = ArrayDeque(List(limit.count) { stamp })
        }
    }
}
