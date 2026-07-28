package com.serverspulse.agent.api.dto

/**
 * Response from `POST /api/v1/ingest`.
 * The backend returns status and optionally a list of commands for the plugin to execute.
 */
data class IngestResponse(
    val status: String,
    val commands: List<CommandPayload>?
)
