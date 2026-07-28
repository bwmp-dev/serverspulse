package com.serverspulse.agent.fabric

import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.server.MinecraftServer

/**
 * Server-level metrics for Fabric using the shared [TickTimingTracker].
 */
class FabricServerIntrospection(
    private val server: MinecraftServer,
    private val tickTracker: TickTimingTracker
) : ServerIntrospection {

    override fun getTps(): Double = tickTracker.getTps()

    override fun getMspt(): Double = tickTracker.getMspt()

    override fun getOnlinePlayers(): Int {
        val playerManager = FabricCompat.invokeNoArg(server, "getPlayerManager")
            ?: FabricCompat.readField(server, "playerManager")
            ?: return 0

        val players = FabricCompat.invokeNoArg(playerManager, "getPlayerList") as? Collection<*>
            ?: FabricCompat.readField(playerManager, "playerList", "players") as? Collection<*>
            ?: return 0

        return players.size
    }

    override fun getMaxPlayers(): Int {
        val playerManager = FabricCompat.invokeNoArg(server, "getPlayerManager")
            ?: FabricCompat.readField(server, "playerManager")
            ?: return 0

        val value = FabricCompat.invokeNoArg(playerManager, "getMaxPlayerCount")
            ?: FabricCompat.readField(playerManager, "maxPlayerCount")
            ?: return 0

        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> 0
        }
    }
}
