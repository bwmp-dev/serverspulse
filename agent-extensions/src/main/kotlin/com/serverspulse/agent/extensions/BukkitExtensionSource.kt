package com.serverspulse.agent.extensions

import com.serverspulse.agent.api.ExtensionSource
import com.serverspulse.agent.api.dto.ExtensionKeys
import com.serverspulse.agent.api.dto.ExtensionStateEntry
import com.serverspulse.agent.api.dto.PlayerExtensionsPayload
import org.bukkit.Bukkit

/**
 * Snapshots rank, balance and mute state for the players currently online.
 *
 * A snapshot rather than a change feed: none of the three plugins this reads
 * offers an event another plugin can subscribe to without depending on it at
 * compile time, and a rank is a value that happens to change rather than a
 * stream of changes.
 *
 * Runs on the main server thread — every API it touches requires that.
 */
class BukkitExtensionSource : ExtensionSource {

    private companion object {
        const val CAPABILITY_LUCKPERMS = "luckperms"
        const val CAPABILITY_VAULT = "vault_economy"
        const val CAPABILITY_PUNISHMENTS = "punishments"

        /** The backend caps a batch at 500 of each kind. */
        const val MAX_ENTRIES = 500
    }

    private val luckPerms = LuckPermsMirror()
    private val vault = VaultMirror()
    private val punishments = PunishmentMirror()

    override fun capabilities(): List<String> {
        val names = mutableListOf<String>()
        if (luckPerms.isAvailable) names.add(CAPABILITY_LUCKPERMS)
        if (vault.isAvailable) names.add(CAPABILITY_VAULT)
        if (punishments.isAvailable) names.add(CAPABILITY_PUNISHMENTS)
        return names
    }

    override fun collect(): PlayerExtensionsPayload {
        val players = Bukkit.getOnlinePlayers()
        val state = ArrayList<ExtensionStateEntry>()

        for (player in players) {
            if (state.size + 2 > MAX_ENTRIES) break
            val uuid = player.uniqueId.toString()

            luckPerms.primaryGroup(player)?.let {
                state.add(ExtensionStateEntry(uuid, ExtensionKeys.LUCKPERMS_PRIMARY_GROUP, valueText = it))
            }
            vault.balance(player)?.let {
                state.add(ExtensionStateEntry(uuid, ExtensionKeys.VAULT_BALANCE, valueNum = it))
            }
        }

        return PlayerExtensionsPayload(
            state = state,
            punishments = punishments.collect(players).take(MAX_ENTRIES)
        )
    }
}
