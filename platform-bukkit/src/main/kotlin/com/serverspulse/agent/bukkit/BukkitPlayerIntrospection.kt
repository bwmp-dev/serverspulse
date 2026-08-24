package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.PlayerIntrospection
import com.serverspulse.agent.api.VersionCapabilities
import org.bukkit.entity.Player
import java.lang.reflect.Method

/**
 * Per-player metrics for Bukkit/Spigot/Paper.
 *
 * Like [BukkitServerIntrospection], everything past the 1.8.8 compile baseline
 * is reached by reflection, so one jar covers every supported server version.
 * Reflected lookups are resolved once per JVM rather than per player per tick.
 */
class BukkitPlayerIntrospection(
    private val player: Player,
    private val capabilities: VersionCapabilities,
    private val activityTracker: PlayerActivityTracker
) : PlayerIntrospection {

    override val uuid: String
        get() = player.uniqueId.toString()

    override val name: String
        get() = player.name

    override fun getPing(): Int? {
        if (!capabilities.supportsPlayerPing) return null
        return readPing(player)
    }

    override fun currentWorld(): String? {
        return try {
            player.world?.name
        } catch (_: Throwable) {
            null
        }
    }

    override fun getClientBrand(): String? {
        if (!capabilities.supportsClientBrand) return null
        return try {
            (CLIENT_BRAND_METHOD?.invoke(player) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    override fun getProtocolVersion(): Int? {
        if (!capabilities.supportsProtocolVersion) return null
        return try {
            (PROTOCOL_VERSION_METHOD?.invoke(player) as? Int)?.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    override fun getLastActivityMillis(): Long? = activityTracker.lastActivityMillis(player.uniqueId)

    companion object {
        /** Paper 1.17+. Absent on Spigot and CraftBukkit. */
        private val CLIENT_BRAND_METHOD: Method? = resolve("org.bukkit.entity.Player", "getClientBrandName")

        /**
         * Paper 1.21.4+ exposes the handshake protocol on Player. Older Paper and
         * every Spigot build do not, and there is no supported substitute.
         */
        private val PROTOCOL_VERSION_METHOD: Method? = resolve("org.bukkit.entity.Player", "getProtocolVersion")

        /** Spigot 1.16.5+ / Paper. Earlier versions only expose the NMS field. */
        private val PING_METHOD: Method? = resolve("org.bukkit.entity.Player", "getPing")

        fun supportsClientBrand(): Boolean = CLIENT_BRAND_METHOD != null

        fun supportsProtocolVersion(): Boolean = PROTOCOL_VERSION_METHOD != null

        /**
         * Ping is available either through the API method or through the NMS
         * handle, so support is probed against a live player rather than
         * assumed from the API surface alone.
         */
        fun supportsPing(sample: Player?): Boolean {
            if (PING_METHOD != null) return true
            return sample != null && readNmsPing(sample) != null
        }

        private fun readPing(player: Player): Int? {
            if (PING_METHOD != null) {
                try {
                    val value = PING_METHOD.invoke(player) as? Int
                    if (value != null && value >= 0) return value
                } catch (_: Throwable) {
                }
            }
            return readNmsPing(player)
        }

        /**
         * Falls back to the `ping` field on the CraftPlayer's NMS handle, which
         * is how the value was reachable before Spigot exposed getPing().
         */
        private fun readNmsPing(player: Player): Int? {
            return try {
                val handle = player.javaClass.getMethod("getHandle").invoke(player) ?: return null
                var current: Class<*>? = handle.javaClass
                while (current != null) {
                    val field = current.declaredFields.firstOrNull { it.name == "ping" || it.name == "latency" }
                    if (field != null) {
                        field.isAccessible = true
                        val value = field.get(handle)
                        return (value as? Int)?.takeIf { it >= 0 }
                    }
                    current = current.superclass
                }
                null
            } catch (_: Throwable) {
                null
            }
        }

        private fun resolve(className: String, methodName: String): Method? {
            return try {
                Class.forName(className).getMethod(methodName)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
