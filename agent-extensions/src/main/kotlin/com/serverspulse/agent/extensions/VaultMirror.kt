package com.serverspulse.agent.extensions

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * Reads each online player's balance through whichever economy plugin is
 * registered against Vault's service manager.
 *
 * Vault itself holds no balances, so this deliberately reads the registered
 * provider rather than looking for a specific economy plugin.
 */
internal class VaultMirror {

    private var handles: Handles? = null

    val isAvailable: Boolean
        get() = resolve() != null

    fun balance(player: Player): Double? {
        val resolved = resolve() ?: return null
        val target: Any = if (resolved.keyedByName) player.name else player
        val value = Reflect.invoke(resolved.getBalance, resolved.economy, target) as? Double ?: return null
        return value.takeIf { it.isFinite() }
    }

    private fun resolve(): Handles? {
        handles?.let { return it }

        @Suppress("UNCHECKED_CAST")
        val economyType = Reflect.type("net.milkbowl.vault.economy.Economy") as? Class<Any> ?: return null
        val registration = try {
            Bukkit.getServicesManager().getRegistration(economyType)
        } catch (_: Throwable) {
            null
        } ?: return null

        val economy = registration.provider ?: return null

        // Vault 1.4 replaced the name-keyed lookup with an OfflinePlayer one and
        // deprecated the old form; economy plugins on either side of that split
        // are still in service, so both are accepted.
        val byPlayer = Reflect.method(economyType, "getBalance", OfflinePlayer::class.java)
        val getBalance = byPlayer ?: Reflect.method(economyType, "getBalance", String::class.java) ?: return null

        return Handles(economy, getBalance, keyedByName = byPlayer == null).also { handles = it }
    }

    private class Handles(val economy: Any, val getBalance: Method, val keyedByName: Boolean)
}
