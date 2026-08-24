package com.serverspulse.agent.core.collector

import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.OperatingSystemMXBean
import java.lang.reflect.Method

/**
 * Collects JVM-level metrics that are platform-agnostic.
 * Uses standard MXBeans available on all JVM implementations.
 */
object SystemMetrics {

    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val gcBeans: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()
    private val osBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()

    // com.sun.management.OperatingSystemMXBean carries the CPU load methods but
    // is not part of the JDK's public API surface and is absent on some JVMs, so
    // it is reached reflectively rather than cast to.
    private val processCpuLoadMethod: Method? = resolveCpuMethod("getProcessCpuLoad")
    private val systemCpuLoadMethod: Method? =
        resolveCpuMethod("getCpuLoad") ?: resolveCpuMethod("getSystemCpuLoad")

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

    /** Returns this JVM's CPU usage as a percentage, or null if unavailable. */
    fun cpuProcessPercent(): Double? = readCpuLoad(processCpuLoadMethod)

    /** Returns whole-machine CPU usage as a percentage, or null if unavailable. */
    fun cpuSystemPercent(): Double? = readCpuLoad(systemCpuLoadMethod)

    /**
     * Returns used space on the filesystem holding [root], in MB.
     *
     * This measures the whole filesystem rather than the server directory:
     * walking a world folder on every collection tick would cost far more than
     * the number is worth, and a server running out of disk runs out of the
     * whole filesystem.
     */
    fun diskUsedMB(root: File): Long? {
        val total = root.totalSpace
        val usable = root.usableSpace
        if (total <= 0L || usable < 0L) return null
        return (total - usable) / (1024 * 1024)
    }

    /** Returns total space on the filesystem holding [root], in MB. */
    fun diskTotalMB(root: File): Long? {
        val total = root.totalSpace
        if (total <= 0L) return null
        return total / (1024 * 1024)
    }

    /** Whether this JVM exposes CPU load at all. */
    fun supportsCpuMetrics(): Boolean = processCpuLoadMethod != null || systemCpuLoadMethod != null

    private fun readCpuLoad(method: Method?): Double? {
        if (method == null) return null
        return try {
            val value = method.invoke(osBean) as? Double ?: return null
            // The bean returns a 0..1 fraction, and a negative value when it has
            // not gathered enough samples yet.
            if (value < 0.0) null else (value * 100.0).coerceIn(0.0, 100.0)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCpuMethod(name: String): Method? {
        return try {
            val method = Class.forName("com.sun.management.OperatingSystemMXBean")
                .getMethod(name)
            if (method.declaringClass.isInstance(osBean)) method else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun totalGcTimeMs(): Long {
        return gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
    }
}
