package com.serverspulse.agent.forge

import com.serverspulse.agent.api.WorldIntrospection
import net.minecraft.world.server.ServerWorld

class ForgeWorldIntrospection(private val level: ServerWorld) : WorldIntrospection {

    override val name: String
        get() = level.dimension().location().path

    override val dimension: String
        get() {
            val key = level.dimension().location().toString()
            return when (key) {
                "minecraft:overworld" -> "overworld"
                "minecraft:the_nether" -> "the_nether"
                "minecraft:the_end"   -> "the_end"
                else                  -> level.dimension().location().path
            }
        }

    override fun getEntityCount(): Int {
        return level.getEntities().count().toInt()
    }

    override fun getLoadedChunks(): Int = level.chunkSource.getLoadedChunksCount()
    override fun getPlayerCount(): Int = level.players().size
}
