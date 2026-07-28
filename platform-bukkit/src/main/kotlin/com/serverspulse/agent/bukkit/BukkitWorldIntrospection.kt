package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.WorldIntrospection
import org.bukkit.World
import java.lang.reflect.Method

/**
 * Per-world metric collection wrapping a Bukkit [World] instance.
 */
class BukkitWorldIntrospection(private val world: World) : WorldIntrospection {

    private val entityCountMethod: Method? = resolveIntMethod("getEntityCount")
    private val chunkCountMethod: Method? = resolveIntMethod("getChunkCount")
    private val playerCountMethod: Method? = resolveIntMethod("getPlayerCount")

    override val name: String
        get() = world.name

    override val dimension: String
        get() = when (world.environment) {
            World.Environment.NORMAL -> "overworld"
            World.Environment.NETHER -> "the_nether"
            World.Environment.THE_END -> "the_end"
            else -> world.environment.name.lowercase()
        }

    override fun getEntityCount(): Int {
        invokeInt(entityCountMethod)?.let { return it }
        return safely(0) { world.entities.size }
    }

    override fun getLoadedChunks(): Int {
        invokeInt(chunkCountMethod)?.let { return it }
        return safely(0) { world.loadedChunks.size }
    }

    override fun getPlayerCount(): Int {
        invokeInt(playerCountMethod)?.let { return it }
        return safely(0) { world.players.size }
    }

    private fun resolveIntMethod(name: String): Method? {
        return try {
            world::class.java.getMethod(name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeInt(method: Method?): Int? {
        if (method == null) return null
        return try {
            val value = method.invoke(world)
            (value as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
    }

    private inline fun safely(defaultValue: Int, supplier: () -> Int): Int {
        return try {
            supplier()
        } catch (_: Throwable) {
            defaultValue
        }
    }
}
