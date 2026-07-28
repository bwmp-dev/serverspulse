package com.serverspulse.agent.core.collector

/**
 * Tracks server tick timing to calculate TPS and MSPT.
 *
 * Platform modules call [onTickStart] and [onTickEnd] from their tick event hooks.
 * This class is shared across Fabric, Forge, and NeoForge (none of which expose
 * a native TPS API like Spigot does).
 *
 * Maintains a rolling window of per-tick durations for stable averages.
 */
class TickTimingTracker {

    companion object {
        private const val WINDOW_SIZE = 100
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MS = 1_000_000.0
    }

    private val tickDurations = LongArray(WINDOW_SIZE) // nanoseconds per tick
    private var tickIndex = 0
    private var tickCount = 0

    @Volatile
    private var tickStartNanos = 0L

    @Volatile
    private var lastTickEndNanos = System.nanoTime()

    /**
     * Call at the start of each server tick.
     */
    fun onTickStart() {
        tickStartNanos = System.nanoTime()
    }

    /**
     * Call at the end of each server tick.
     */
    fun onTickEnd() {
        val now = System.nanoTime()
        val start = tickStartNanos
        if (start <= 0) return

        val duration = now - start
        if (duration <= 0) return

        tickDurations[tickIndex % WINDOW_SIZE] = duration
        tickIndex++
        if (tickCount < WINDOW_SIZE) tickCount++
        lastTickEndNanos = now
    }

    /**
     * Returns the current TPS based on average tick duration.
     * A tick taking 50ms = 20 TPS. A tick taking 100ms = 10 TPS.
     */
    fun getTps(): Double {
        if (tickCount == 0) return 20.0
        val avgNanos = averageTickNanos()
        if (avgNanos <= 0) return 20.0
        // TPS = 1 second / avg tick duration, capped at 20
        return (NANOS_PER_SECOND / avgNanos).coerceIn(0.0, 20.0)
    }

    /**
     * Returns the average milliseconds per tick (MSPT).
     */
    fun getMspt(): Double {
        if (tickCount == 0) return 0.0
        return averageTickNanos() / NANOS_PER_MS
    }

    private fun averageTickNanos(): Double {
        if (tickCount == 0) return 50_000_000.0 // default 50ms
        var sum = 0L
        for (i in 0 until tickCount) {
            sum += tickDurations[i]
        }
        return sum.toDouble() / tickCount
    }
}
