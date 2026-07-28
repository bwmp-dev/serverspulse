package com.serverspulse.agent.forge

import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.server.MinecraftServer

/**
 * Server-level metrics for Forge using the shared [TickTimingTracker].
 */
class ForgeServerIntrospection(
    private val server: MinecraftServer,
    private val tickTracker: TickTimingTracker
) : ServerIntrospection {

    override fun getTps(): Double = tickTracker.getTps()

    override fun getMspt(): Double = tickTracker.getMspt()

    override fun getOnlinePlayers(): Int = server.playerList.playerCount

    override fun getMaxPlayers(): Int = server.playerList.maxPlayers
}
