package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.ServerIntrospection
import net.md_5.bungee.api.ProxyServer

/**
 * Server-level metrics for a BungeeCord or Waterfall proxy.
 *
 * A proxy has no tick loop, so there is no TPS and no MSPT to read. Both are
 * reported as absent rather than as a healthy 20.0, which would show a green
 * performance chart for something that was never measured.
 */
class BungeeServerIntrospection(private val proxy: ProxyServer) : ServerIntrospection {

    override fun getTps(): Double? = null

    override fun getMspt(): Double? = null

    override fun getOnlinePlayers(): Int = proxy.onlineCount

    /** Bungee reports -1 for an unlimited proxy, which is not a slot count. */
    override fun getMaxPlayers(): Int = proxy.config.playerLimit.coerceAtLeast(0)
}
