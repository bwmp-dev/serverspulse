package com.serverspulse.agent.forge

import com.serverspulse.agent.api.WorldIntrospection
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

class ForgeWorldIntrospection(private val level: ServerLevel) : WorldIntrospection {

    override val name: String
        get() = level.dimension().location().path

    override val dimension: String
        get() = when (level.dimension()) {
            Level.OVERWORLD -> "overworld"
            Level.NETHER    -> "the_nether"
            Level.END       -> "the_end"
            else            -> level.dimension().location().path
        }

    override fun getEntityCount(): Int {
        var count = 0
        level.allEntities.forEach { _ -> count++ }
        return count
    }

    override fun getLoadedChunks(): Int = level.chunkSource.getLoadedChunksCount()
    override fun getPlayerCount(): Int = level.players().size
}
