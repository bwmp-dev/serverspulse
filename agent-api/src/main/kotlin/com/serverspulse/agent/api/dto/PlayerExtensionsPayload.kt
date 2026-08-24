package com.serverspulse.agent.api.dto

/**
 * Body of `POST /api/v1/ingest/player-extensions`: a snapshot of state
 * mirrored from other plugins on the server.
 */
data class PlayerExtensionsPayload(
    val state: List<ExtensionStateEntry>,
    val punishments: List<PunishmentEntry>
)

/**
 * One `(player, key)` pair. Exactly one of [valueText] or [valueNum] carries
 * the reading; the backend rejects an entry that has neither.
 *
 * @param key one of the identifiers in [ExtensionKeys]
 */
data class ExtensionStateEntry(
    val playerUuid: String,
    val key: String,
    val valueText: String? = null,
    val valueNum: Double? = null
)

/**
 * One punishment record.
 *
 * [source] and [sourceId] together identify the record in the plugin it was
 * mirrored from, which is what makes a re-sync idempotent instead of
 * duplicating history.
 *
 * @param type one of the identifiers in [PunishmentTypes]
 */
data class PunishmentEntry(
    val playerUuid: String,
    val type: String,
    val reason: String? = null,
    val issuedBy: String? = null,
    val issuedAt: String,
    val expiresAt: String? = null,
    val active: Boolean? = null,
    val source: String,
    val sourceId: String
)

/** Shared contract with the backend's `storage/player_extensions.go`. */
object ExtensionKeys {
    const val LUCKPERMS_PRIMARY_GROUP = "luckperms.primary_group"
    const val VAULT_BALANCE = "vault.balance"
}

/** The only values the backend's `player_punishments` CHECK constraint accepts. */
object PunishmentTypes {
    const val BAN = "ban"
    const val TEMPBAN = "tempban"
    const val MUTE = "mute"
    const val TEMPMUTE = "tempmute"
    const val KICK = "kick"
    const val WARN = "warn"
}
