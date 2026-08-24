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
 * @param clientBrand     Value of the `minecraft:brand` channel, or null when
 *                        the platform cannot read it. This is what identifies a
 *                        Bedrock player arriving through a translating proxy.
 * @param clientVersion   Human-readable client version, when the platform can
 *                        resolve one.
 * @param protocolVersion Handshake protocol number, or null when unavailable.
 * @param countryCode     ISO-3166-1 alpha-2 country the join came from (join
 *                        only), or null when geolocation is disabled, the
 *                        database is unavailable or the address is unresolvable.
 *                        The address itself is resolved on the server and never
 *                        transmitted.
 */
data class PlayerEventPayload(
    val event: String,
    val playerUuid: String,
    val username: String,
    val hostname: String?,
    val clientBrand: String? = null,
    val clientVersion: String? = null,
    val protocolVersion: Int? = null,
    val countryCode: String? = null
)
