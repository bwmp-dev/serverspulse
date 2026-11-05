package com.serverspulse.agent.api

import com.serverspulse.agent.api.dto.CustomTelemetry
import com.serverspulse.agent.api.dto.CustomMetric
import com.serverspulse.agent.api.dto.CustomEvent
import java.time.Instant
import java.util.UUID

/** Bounded, process-local telemetry. Calls return false when disabled or rejected. */
object ServersPulse {
    private val namePattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")
    private val names = mutableSetOf<String>()
    private val metrics = linkedMapOf<String, CustomMetric>()
    private val events = mutableListOf<CustomEvent>()
    private var active = false
    private var calls = 0
    private var window = System.nanoTime()

    @JvmStatic @Synchronized fun gauge(name: String, value: Double): Boolean = metric(name, value, "gauge")
    @JvmStatic @Synchronized fun counter(name: String, delta: Double): Boolean = metric(name, delta, "counter")

    private fun metric(name: String, value: Double, kind: String): Boolean {
        if (!active || !namePattern.matches(name) || !value.isFinite() || (kind == "counter" && value < 0)) return false
        val key = "$kind:$name"
        if (key !in names && names.size >= 64) return false
        if (!allowCall()) return false
        val total = if (kind == "counter") (metrics[key]?.value ?: 0.0) + value else value
        if (!total.isFinite()) return false
        names.add(key)
        metrics[key] = CustomMetric(name, kind, total)
        return true
    }

    @JvmStatic @Synchronized fun event(type: String, fields: Map<String, Any?>): Boolean {
        if (!active || !namePattern.matches(type) || fields.size > 16 || events.size >= 100) return false
        val copy = linkedMapOf<String, Any?>()
        for ((key, value) in fields) {
            if (!namePattern.matches(key)) return false
            if (value != null && value !is String && value !is Boolean && value !is Number) return false
            if (value is String && value.toByteArray(Charsets.UTF_8).size > 256) return false
            if (value is Number && !value.toDouble().isFinite()) return false
            copy[key] = if (value is Number) value.toDouble() else value
        }
        if (!allowCall()) return false
        events.add(CustomEvent(Instant.now().toString(), type, copy))
        return true
    }

    @JvmStatic fun annotate(text: String): Boolean = event("annotation", mapOf("text" to text))

    private fun allowCall(): Boolean {
        val now = System.nanoTime()
        if (now - window >= 60_000_000_000L) { window = now; calls = 0 }
        if (calls >= 10000) return false
        calls++
        return true
    }

    @JvmStatic @Synchronized fun setRunning(running: Boolean) {
        active = running
        metrics.clear()
        events.clear()
        names.clear()
        calls = 0
        window = System.nanoTime()
    }

    @JvmStatic @Synchronized fun drain(): CustomTelemetry? {
        if (metrics.isEmpty() && events.isEmpty()) return null
        val batch = CustomTelemetry(UUID.randomUUID().toString(), metrics.values.toList(), events.toList())
        metrics.clear()
        events.clear()
        return batch
    }
}
