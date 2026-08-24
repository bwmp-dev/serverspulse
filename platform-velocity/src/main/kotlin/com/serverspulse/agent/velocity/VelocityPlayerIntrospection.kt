package com.serverspulse.agent.velocity

import com.serverspulse.agent.api.PlayerIntrospection
import com.velocitypowered.api.proxy.Player

/**
 * Per-player metrics on a Velocity proxy.
 *
 * Latency is the proxy-to-player leg, which is the one a proxy can actually
 * measure and the one that reflects the player's own connection. World and AFK
 * state belong to the backend server the player is on and are left to that
 * server's agent to report.
 */
class VelocityPlayerIntrospection(private val player: Player) : PlayerIntrospection {

    override val uuid: String
        get() = player.uniqueId.toString()

    override val name: String
        get() = player.username

    override fun getPing(): Int? {
        val ping = player.ping
        if (ping < 0 || ping > Int.MAX_VALUE) return null
        return ping.toInt()
    }

    override fun getClientBrand(): String? = player.clientBrand?.takeIf { it.isNotBlank() }

    override fun getProtocolVersion(): Int? = player.protocolVersion.protocol.takeIf { it > 0 }
}
