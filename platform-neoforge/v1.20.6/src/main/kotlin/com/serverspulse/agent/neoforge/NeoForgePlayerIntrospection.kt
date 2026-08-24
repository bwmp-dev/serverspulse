package com.serverspulse.agent.neoforge

import com.serverspulse.agent.api.PlayerIntrospection
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player metrics for NeoForge.
 *
 * Latency and dimension are read reflectively rather than through the mapped
 * API: this file is compiled unchanged by the root module and by each
 * per-version variant, and those members have moved between a field and an
 * accessor across the versions those variants target.
 *
 * The client brand and handshake protocol live on the connection handler with
 * no accessor of any kind, so they are not reported and the module declares
 * them unsupported rather than guessing.
 */
class NeoForgePlayerIntrospection(
    private val player: ServerPlayer,
    private val activityTracker: NeoForgePlayerActivityTracker
) : PlayerIntrospection {

    override val uuid: String
        get() = player.uuid.toString()

    override val name: String
        get() = player.gameProfile.name ?: ""

    override fun getPing(): Int? {
        val connection = readMember(player, "connection") ?: return null
        val value = readMember(connection, "latency")
            ?: readMember(connection, "latency_")
            ?: return null
        return (value as? Number)?.toInt()?.takeIf { it >= 0 }
    }

    override fun currentWorld(): String? {
        // ResourceKey's accessor for its underlying identifier was renamed in
        // the 26.x line, so it is resolved by name rather than called directly.
        val dimension = try {
            player.level().dimension()
        } catch (_: Throwable) {
            null
        } ?: return null

        val identifier = readMember(dimension, "location")
            ?: readMember(dimension, "identifier")
            ?: readMember(dimension, "value")
            ?: return null

        return identifier.toString().takeIf { it.isNotBlank() }
    }

    /**
     * Records this sample's position and returns when the player last moved.
     * NeoForge offers this module no movement event without a mixin, so the
     * observation and the query are the same call.
     */
    override fun getLastActivityMillis(): Long? {
        return try {
            activityTracker.observe(uuid, player.x, player.y, player.z)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /**
         * The address a player connected from, for local country resolution.
         *
         * Reached through the connection rather than through the player because
         * that is where it lives, and reflectively because the two hops between
         * them have been both a field and an accessor across the versions the
         * per-version variants of this module target.
         */
        fun remoteAddress(player: ServerPlayer): java.net.InetAddress? {
            val listener = readMember(player, "connection") ?: return null
            val connection = readMember(listener, "connection") ?: return null
            val socket = readMember(connection, "getRemoteAddress")
                ?: readMember(connection, "address")
                ?: return null
            return (socket as? java.net.InetSocketAddress)?.address
        }

        /**
         * Members are cached per (class, name) because this runs once per player
         * per collection tick and a miss walks the whole hierarchy.
         */
        val cache = ConcurrentHashMap<String, java.lang.reflect.AccessibleObject?>()

        private fun readMember(target: Any, name: String): Any? {
            val key = target.javaClass.name + "#" + name
            val member = cache.getOrPut(key) { resolveMember(target.javaClass, name) } ?: return null

            return try {
                when (member) {
                    is java.lang.reflect.Method -> member.invoke(target)
                    is java.lang.reflect.Field -> member.get(target)
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun resolveMember(type: Class<*>, name: String): java.lang.reflect.AccessibleObject? {
            var current: Class<*>? = type
            while (current != null) {
                current.declaredMethods
                    .firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.let {
                        it.isAccessible = true
                        return it
                    }
                current.declaredFields
                    .firstOrNull { it.name == name }
                    ?.let {
                        it.isAccessible = true
                        return it
                    }
                current = current.superclass
            }
            return null
        }
    }
}
