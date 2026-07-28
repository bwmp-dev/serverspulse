package com.serverspulse.agent.neoforge

import com.serverspulse.agent.api.WorldIntrospection
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

class NeoForgeWorldIntrospection(private val level: ServerLevel) : WorldIntrospection {

    override val name: String
        get() = resolveDimensionPath()

    override val dimension: String
        get() = when (level.dimension()) {
            Level.OVERWORLD -> "overworld"
            Level.NETHER -> "the_nether"
            Level.END -> "the_end"
            else -> resolveDimensionPath()
        }

    override fun getEntityCount(): Int {
        var count = 0
        level.allEntities.forEach { _ -> count++ }
        return count
    }

    override fun getLoadedChunks(): Int = level.chunkSource.getLoadedChunksCount()

    override fun getPlayerCount(): Int = level.players().size

    private fun resolveDimensionPath(): String {
        val key = level.dimension()
        return tryGetPathFromResourceKey(key)
            ?: tryGetPathFromResourceLocation(key)
            ?: key.toString()
    }

    private fun tryGetPathFromResourceKey(key: Any): String? {
        return try {
            val locationMethod = key.javaClass.methods.firstOrNull {
                it.name == "location" && it.parameterCount == 0
            } ?: return null

            val location = locationMethod.invoke(key) ?: return null
            tryGetPathFromResourceLocation(location)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryGetPathFromResourceLocation(resourceLocation: Any): String? {
        return try {
            val pathMethod = resourceLocation.javaClass.methods.firstOrNull {
                (it.name == "getPath" || it.name == "path") && it.parameterCount == 0
            } ?: return null

            pathMethod.invoke(resourceLocation)?.toString()
        } catch (_: Throwable) {
            null
        }
    }
}
