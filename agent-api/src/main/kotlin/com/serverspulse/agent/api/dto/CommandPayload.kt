package com.serverspulse.agent.api.dto

/**
 * A command returned by the backend in the ingest response.
 * The plugin should execute the requested action and report results.
 */
data class CommandPayload(
    val type: String,                // e.g. "spark_profile"
    val incidentId: String?,
    val durationSeconds: Int?
)
