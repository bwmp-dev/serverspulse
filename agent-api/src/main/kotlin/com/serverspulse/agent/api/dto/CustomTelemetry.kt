package com.serverspulse.agent.api.dto

data class CustomMetric(val name: String, val kind: String, val value: Double)
data class CustomEvent(val timestamp: String, val type: String, val fields: Map<String, Any?>)
data class CustomTelemetry(val batchId: String, val metrics: List<CustomMetric>, val events: List<CustomEvent>)
