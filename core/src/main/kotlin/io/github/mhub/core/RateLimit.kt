package io.github.mhub.core

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How long one request may wait out the server's per-client quota (HTTP 429)
 * before the error reaches the caller. Mirrors the web client's `rateLimit`
 * option and iOS `RateLimitPolicy`; cases are in `contract/rate-limit-cases.json`.
 *
 * [maxWaitMs] is the total time spent waiting across retries (0 never waits);
 * [onWait] runs before each wait with the wait in ms, e.g. to tell the listener.
 */
class RateLimitPolicy(val maxWaitMs: Long, val onWait: ((Long) -> Unit)? = null) {
    companion object {
        /** Ordinary requests: short, so an interactive action still answers. */
        val Standard = RateLimitPolicy(10_000)
        /** Next-song prefetch gives up at once; the player retries it later. */
        val Prefetch = RateLimitPolicy(0)
        /** A song the listener asked for waits up to two minutes. */
        fun playback(onWait: ((Long) -> Unit)? = null) = RateLimitPolicy(120_000, onWait)
        /** Importing a playlist is background work and waits up to five minutes. */
        fun importing(onWait: ((Long) -> Unit)? = null) = RateLimitPolicy(300_000, onWait)
    }
}

object RateLimit {
    private const val MIN_RETRY_MS = 1000L
    /** Prefetch retry backoff; a 429 never retries sooner than its Retry-After. */
    private val prefetchBackoffMs = longArrayOf(5000, 15000, 30000)

    /** `Retry-After` as milliseconds: delta-seconds or an HTTP date, at least a second. */
    fun retryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val text = header.orEmpty().trim()
        if (text.isEmpty()) return MIN_RETRY_MS
        if (text.all { it in '0'..'9' }) return maxOf(MIN_RETRY_MS, (text.toLongOrNull() ?: 0) * 1000)
        val date = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: return MIN_RETRY_MS
        return maxOf(MIN_RETRY_MS, Duration.between(Instant.ofEpochMilli(nowMs), date).toMillis())
    }

    /** Whole seconds to show the listener for a wait (rounded up). */
    fun seconds(ms: Long): Long = (ms + 999) / 1000

    /** Delay before the next prefetch attempt after [attempt] failures. */
    fun prefetchDelayMs(attempt: Int, retryAfterMs: Long): Long =
        maxOf(prefetchBackoffMs[attempt.coerceIn(0, prefetchBackoffMs.size - 1)], retryAfterMs)

    /** Runs [request], waiting out 429 answers within [policy]; cancelling the coroutine stops the wait. */
    suspend fun <T> run(policy: RateLimitPolicy, sleep: suspend (Long) -> Unit, request: suspend () -> T): T {
        var waited = 0L
        while (true) {
            try {
                return request()
            } catch (limited: ApiException.RateLimited) {
                if (waited + limited.retryAfterMs > policy.maxWaitMs) throw limited
                policy.onWait?.invoke(limited.retryAfterMs)
                sleep(limited.retryAfterMs)
                waited += limited.retryAfterMs
            }
        }
    }
}
