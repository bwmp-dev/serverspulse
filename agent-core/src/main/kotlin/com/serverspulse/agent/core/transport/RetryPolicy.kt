package com.serverspulse.agent.core.transport

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff retry policy with jitter.
 */
class RetryPolicy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val jitterFactor: Double = 0.2
) {
    /**
     * Calculates the delay before the next retry attempt.
     * @param attempt 0-based attempt index
     * @return delay in milliseconds, or null if retries are exhausted
     */
    fun delayForAttempt(attempt: Int): Long? {
        if (attempt >= maxRetries) return null

        val exponentialDelay = baseDelayMs * 2.0.pow(attempt).toLong()
        val capped = min(exponentialDelay, maxDelayMs)
        val jitter = (capped * jitterFactor * Math.random()).toLong()
        return capped + jitter
    }
}
