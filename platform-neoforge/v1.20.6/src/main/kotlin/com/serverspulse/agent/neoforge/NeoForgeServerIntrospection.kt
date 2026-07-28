package com.serverspulse.agent.neoforge

import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.server.MinecraftServer

class NeoForgeServerIntrospection(
    private val server: MinecraftServer,
    private val tickTracker: TickTimingTracker
) : ServerIntrospection {

    override fun getTps(): Double = tickTracker.getTps()

    override fun getMspt(): Double = tickTracker.getMspt()

    override fun getOnlinePlayers(): Int = server.playerList.playerCount

    override fun getMaxPlayers(): Int = server.playerList.maxPlayers
}
