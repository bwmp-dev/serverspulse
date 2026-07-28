package com.serverspulse.agent.api.dto

/**
 * Per-world metrics included in a [ServerSnapshot].
 * Field names match the backend ingestion contract exactly.
 */
data class WorldSnapshot(
    val name: String,
    val dimension: String,
    val entityCount: Int,
    val loadedChunks: Int,
    val playerCount: Int
)
