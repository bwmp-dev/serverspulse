package com.serverspulse.agent.core.collector

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory

/**
 * Collects JVM-level metrics that are platform-agnostic.
 * Uses standard MXBeans available on all JVM implementations.
 */
object SystemMetrics {

    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val gcBeans: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()

    /** Tracks last-observed total GC time so we can compute delta. */
    @Volatile
    private var lastGcTimeMs: Long = totalGcTimeMs()

    /** Returns heap memory currently used, in MB. */
    fun memoryUsedMB(): Long {
        return memoryBean.heapMemoryUsage.used / (1024 * 1024)
    }

    /** Returns maximum heap memory, in MB. */
    fun memoryMaxMB(): Long {
        val max = memoryBean.heapMemoryUsage.max
        // max can be -1 if undefined; fall back to committed
        return if (max > 0) {
            max / (1024 * 1024)
        } else {
            memoryBean.heapMemoryUsage.committed / (1024 * 1024)
        }
    }

    /**
     * Returns GC pause time accumulated since the last call, in milliseconds.
     * Returns null on the first call (no delta yet) or if GC data is unavailable.
     */
    fun gcPauseDeltaMs(): Long? {
        val current = totalGcTimeMs()
        val previous = lastGcTimeMs
        lastGcTimeMs = current

        // Skip the first sample - no meaningful delta
        if (previous == 0L && current == 0L) return null
        val delta = current - previous
        return if (delta >= 0) delta else null
    }

    private fun totalGcTimeMs(): Long {
        return gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
    }
}
