package com.serverspulse.agent.api.dto

/**
 * The complete metrics snapshot sent to `POST /api/v1/ingest`.
 * Field names match the backend ingestion contract exactly.
 *
 * The server is identified by the `X-API-Key` header, not by any field in this payload.
 */
data class ServerSnapshot(
    val timestamp: String,       // RFC3339 / ISO-8601
    // Null on a proxy, which has no tick loop. Reporting 20.0 there would
    // manufacture a healthy reading for something that was never measured.
    val tps: Double?,
    val mspt: Double?,
    val memoryUsedMB: Long,
    val memoryMaxMB: Long,
    val gcPauseMs: Long?,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val platform: String,
    val mcVersion: String,
    val worlds: List<WorldSnapshot>,
    // CPU and disk come from JVM APIs that are not present on every runtime.
    // Null means "this JVM does not expose it", which the backend stores as
    // NULL rather than as a reading of zero.
    val cpuProcessPct: Double? = null,
    val cpuSystemPct: Double? = null,
    val diskUsedMB: Long? = null,
    val diskTotalMB: Long? = null
)
