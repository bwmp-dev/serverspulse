package com.serverspulse.agent.forge

import com.serverspulse.agent.api.WorldIntrospection
import net.minecraft.world.WorldServer

class ForgeWorldIntrospection(private val world: WorldServer) : WorldIntrospection {

    override val name: String
        get() = world.provider.dimensionType.getName()

    override val dimension: String
        get() = when (world.provider.dimension) {
            0    -> "overworld"
            -1   -> "the_nether"
            1    -> "the_end"
            else -> world.provider.dimensionType.getName()
        }

    override fun getEntityCount(): Int = world.loadedEntityList.size

    override fun getLoadedChunks(): Int = world.chunkProvider.loadedChunkCount

    override fun getPlayerCount(): Int = world.playerEntities.size
}
