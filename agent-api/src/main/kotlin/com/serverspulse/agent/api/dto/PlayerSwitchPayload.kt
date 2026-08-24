package com.serverspulse.agent.api.dto

/**
 * One entry in the batch sent to `POST /api/v1/ingest/player-switches`.
 *
 * Emitted by the proxy modules only. [reason] decides which of the two server
 * names is present:
 *
 * - `join`       — arrival on the network; [fromServer] is null
 * - `switch`     — a backend transition; both names are present
 * - `disconnect` — leaving the network; [toServer] is null
 *
 * @param at RFC3339 timestamp. The backend rejects anything older than 24h, so
 *           buffered entries are dropped rather than held past that.
 */
data class PlayerSwitchPayload(
    val playerUuid: String,
    val fromServer: String?,
    val toServer: String?,
    val at: String,
    val reason: String
) {
    companion object {
        const val REASON_JOIN = "join"
        const val REASON_SWITCH = "switch"
        const val REASON_DISCONNECT = "disconnect"

        /** The backend truncates nothing; it rejects the whole batch instead. */
        const val MAX_SERVER_NAME_LENGTH = 64
    }
}
