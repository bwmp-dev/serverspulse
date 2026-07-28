package com.serverspulse.agent.bukkit

import org.bukkit.plugin.java.JavaPlugin

/**
 * Fallback TPS calculator for servers that lack `Bukkit.getTPS()` (pre-Spigot 1.16).
 *
 * Runs a repeating task every 20 ticks and measures the real wall-clock time that elapsed.
 * If 20 ticks took exactly 1000ms, TPS is 20.0. If they took 2000ms, TPS is 10.0.
 *
 * Maintains a rolling average over the last [WINDOW_SIZE] samples for stability.
 */
class TickCounter(plugin: JavaPlugin) {

    companion object {
        /** Number of ticks between each sample. */
        private const val SAMPLE_INTERVAL_TICKS = 20L

        /** Number of samples to keep in the rolling window. */
        private const val WINDOW_SIZE = 10
    }

    private val samples = DoubleArray(WINDOW_SIZE)
    private var sampleIndex = 0
    private var sampleCount = 0

    @Volatile
    private var lastPollNanos = System.nanoTime()

    init {
        // Schedule a task on the main server thread that fires every 20 ticks
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.nanoTime()
            val elapsedNanos = now - lastPollNanos
            lastPollNanos = now

            // Avoid division by zero on the very first tick or nanoTime weirdness
            if (elapsedNanos <= 0) return@Runnable

            val elapsedSeconds = elapsedNanos / 1_000_000_000.0
            val tps = (SAMPLE_INTERVAL_TICKS / elapsedSeconds).coerceIn(0.0, 20.0)

            samples[sampleIndex % WINDOW_SIZE] = tps
            sampleIndex++
            if (sampleCount < WINDOW_SIZE) sampleCount++
        }, SAMPLE_INTERVAL_TICKS, SAMPLE_INTERVAL_TICKS)
    }

    /**
     * Returns the rolling-average TPS. Returns 20.0 if no samples have been collected yet.
     */
    fun getTps(): Double {
        if (sampleCount == 0) return 20.0
        var sum = 0.0
        for (i in 0 until sampleCount) {
            sum += samples[i]
        }
        return sum / sampleCount
    }
}
