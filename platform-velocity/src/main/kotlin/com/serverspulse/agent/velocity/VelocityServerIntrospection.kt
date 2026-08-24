package com.serverspulse.agent.velocity

import com.serverspulse.agent.api.ServerIntrospection
import com.velocitypowered.api.proxy.ProxyServer

/**
 * Server-level metrics for a Velocity proxy.
 *
 * A proxy has no tick loop, so there is no TPS and no MSPT to read. Both are
 * reported as absent rather than as a healthy 20.0, which would show a green
 * performance chart for something that was never measured.
 */
class VelocityServerIntrospection(private val proxy: ProxyServer) : ServerIntrospection {

    override fun getTps(): Double? = null

    override fun getMspt(): Double? = null

    override fun getOnlinePlayers(): Int = proxy.playerCount

    override fun getMaxPlayers(): Int = proxy.configuration.showMaxPlayers.coerceAtLeast(0)
}
