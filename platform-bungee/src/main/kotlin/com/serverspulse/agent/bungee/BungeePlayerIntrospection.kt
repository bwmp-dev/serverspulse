package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.PlayerIntrospection
import net.md_5.bungee.api.connection.ProxiedPlayer

/**
 * Per-player metrics on a BungeeCord or Waterfall proxy.
 *
 * Latency is the proxy-to-player leg, which is the one a proxy can measure and
 * the one that reflects the player's own connection. World and AFK state belong
 * to the backend server the player is on. The client brand is not exposed by
 * this API at all, so it is left to the backend server's agent as well.
 */
class BungeePlayerIntrospection(private val player: ProxiedPlayer) : PlayerIntrospection {

    override val uuid: String
        get() = player.uniqueId.toString()

    override val name: String
        get() = player.name

    override fun getPing(): Int? = player.ping.takeIf { it >= 0 }

    override fun getProtocolVersion(): Int? = player.pendingConnection.version.takeIf { it > 0 }
}
