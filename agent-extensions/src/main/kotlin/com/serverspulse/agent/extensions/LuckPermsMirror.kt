package com.serverspulse.agent.extensions

import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

/**
 * Reads each online player's LuckPerms primary group.
 *
 * Only the cached user is read: `UserManager.getUser` returns the copy
 * LuckPerms already holds for an online player, so there is no database round
 * trip and nothing here can block the server thread.
 *
 * Everything is resolved on first use rather than in the constructor, because
 * the agent may well be enabled before LuckPerms is and a lookup that happened
 * once at startup would then report "not installed" for the life of the server.
 */
internal class LuckPermsMirror {

    private var handles: Handles? = null

    val isAvailable: Boolean
        get() = resolve() != null

    fun primaryGroup(player: Player): String? {
        val resolved = resolve() ?: return null
        val manager = Reflect.invoke(resolved.userManager, resolved.api) ?: return null
        val user = Reflect.invoke(resolved.getUser, manager, player.uniqueId) ?: return null
        return (Reflect.invoke(resolved.primaryGroup, user) as? String)?.takeIf { it.isNotBlank() }
    }

    private fun resolve(): Handles? {
        handles?.let { return it }

        // Methods come from the API interfaces, not from the returned instance:
        // LuckPerms' implementing classes are internal and not reflectively
        // accessible from another plugin's classloader.
        val provider = Reflect.type("net.luckperms.api.LuckPermsProvider") ?: return null
        val api = Reflect.invoke(Reflect.method(provider, "get"), null) ?: return null
        val userManager = Reflect.method(Reflect.type("net.luckperms.api.LuckPerms"), "getUserManager") ?: return null
        val getUser = Reflect.method(
            Reflect.type("net.luckperms.api.model.user.UserManager"), "getUser", UUID::class.java
        ) ?: return null
        val primaryGroup = Reflect.method(
            Reflect.type("net.luckperms.api.model.user.User"), "getPrimaryGroup"
        ) ?: return null

        return Handles(api, userManager, getUser, primaryGroup).also { handles = it }
    }

    private class Handles(
        val api: Any,
        val userManager: Method,
        val getUser: Method,
        val primaryGroup: Method
    )
}
