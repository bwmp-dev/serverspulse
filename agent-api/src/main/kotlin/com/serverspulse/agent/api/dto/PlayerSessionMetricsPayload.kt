package com.serverspulse.agent.api.dto

/**
 * One entry in the batch sent to `POST /api/v1/ingest/player-sessions`.
 *
 * Counters are cumulative for the session so far, not deltas since the last
 * flush. Combined with a last-write-wins upsert on the backend that makes a
 * retried or duplicated flush harmless, and bounds what a server crash can lose
 * to a single flush interval.
 *
 * A null counter means the agent cannot measure it on this platform, which the
 * backend stores as NULL rather than as zero.
 *
 * @param playerUuid   Minecraft UUID string, with dashes
 * @param afkSeconds   Seconds of this session spent past the AFK threshold
 * @param pingMin      Lowest latency sampled this session, in milliseconds
 * @param pingAvg      Mean of the latency samples taken this session
 * @param pingMax      Highest latency sampled this session, in milliseconds
 * @param worldSeconds Seconds spent in each world this session, keyed by name
 */
data class PlayerSessionMetricsPayload(
    val playerUuid: String,
    val afkSeconds: Int? = null,
    val pingMin: Int? = null,
    val pingAvg: Int? = null,
    val pingMax: Int? = null,
    val worldSeconds: Map<String, Int>? = null
)
