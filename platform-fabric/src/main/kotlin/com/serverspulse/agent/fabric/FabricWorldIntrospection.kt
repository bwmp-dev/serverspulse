package com.serverspulse.agent.fabric

import com.serverspulse.agent.api.WorldIntrospection
import net.minecraft.server.world.ServerWorld

/**
 * Per-world metrics wrapping a Fabric [ServerWorld].
 */
class FabricWorldIntrospection(private val world: ServerWorld) : WorldIntrospection {

    override val name: String
        get() = resolveWorldIdentifier()

    override val dimension: String
        get() = resolveWorldIdentifier()

    override fun getEntityCount(): Int {
        val iterable = FabricCompat.invokeNoArg(world, "iterateEntities") as? Iterable<*>
            ?: FabricCompat.invokeNoArg(world, "getEntities") as? Iterable<*>
            ?: return 0
        return iterable.count()
    }

    override fun getLoadedChunks(): Int {
        val chunkManager = FabricCompat.invokeNoArg(world, "getChunkManager")
            ?: FabricCompat.readField(world, "chunkManager")
            ?: return 0

        val value = FabricCompat.invokeNoArg(chunkManager, "getLoadedChunkCount")
            ?: FabricCompat.readField(chunkManager, "loadedChunkCount")
            ?: return 0

        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> 0
        }
    }

    override fun getPlayerCount(): Int {
        val players = FabricCompat.invokeNoArg(world, "getPlayers") as? Collection<*>
            ?: FabricCompat.readField(world, "players") as? Collection<*>
            ?: return 0
        return players.size
    }

    private fun resolveWorldIdentifier(): String {
        val registryKey = FabricCompat.invokeNoArg(world, "getRegistryKey")
            ?: FabricCompat.readField(world, "registryKey")

        if (registryKey != null) {
            val value = FabricCompat.invokeNoArg(registryKey, "getValue")
                ?: FabricCompat.readField(registryKey, "value")
            if (value != null) {
                val path = FabricCompat.invokeNoArg(value, "getPath")
                    ?: FabricCompat.readField(value, "path")
                if (path is String && path.isNotBlank()) {
                    return path
                }
            }
        }

        val dimension = FabricCompat.invokeNoArg(world, "getDimension") ?: return "overworld"
        val dimensionName = FabricCompat.invokeNoArg(dimension, "getName") as? String
            ?: dimension.toString()
        val normalized = dimensionName.lowercase()

        return when {
            normalized.contains("nether") -> "the_nether"
            normalized.contains("end") -> "the_end"
            normalized.contains("overworld") -> "overworld"
            else -> dimensionName
        }
    }
}
