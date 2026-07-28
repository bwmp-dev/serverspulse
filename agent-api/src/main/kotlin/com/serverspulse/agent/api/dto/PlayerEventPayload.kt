package com.serverspulse.agent.api.dto

/**
 * Payload sent to `POST /api/v1/ingest/player-event` on every player
 * join or leave.
 *
 * @param event      "join" or "leave"
 * @param playerUuid Minecraft player UUID (with dashes, e.g. "069a79f4-…")
 * @param username   Current in-game name
 * @param hostname   The server address the player connected to (join only,
 *                   null on leave or when unavailable on the platform).
 */
data class PlayerEventPayload(
    val event: String,
    val playerUuid: String,
    val username: String,
    val hostname: String?
)
