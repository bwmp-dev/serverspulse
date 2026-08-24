package com.serverspulse.agent.fabric

import com.serverspulse.agent.api.PlayerIntrospection

/**
 * Per-player metrics for Fabric.
 *
 * Like the rest of this module it goes through [FabricCompat] rather than
 * against mapped names, so one jar keeps working across Yarn/Mojang mapping
 * changes and across Minecraft versions that rename these members.
 *
 * The client brand and handshake protocol are not reachable without a mixin
 * into the connection handler, so they are not reported here and the module
 * declares them unsupported.
 */
class FabricPlayerIntrospection(
    private val serverPlayer: Any,
    private val activityTracker: FabricPlayerActivityTracker
) : PlayerIntrospection {

    override val uuid: String
        get() = readUuid() ?: ""

    override val name: String
        get() = readName() ?: ""

    override fun getPing(): Int? {
        val value = FabricCompat.invokeNoArg(serverPlayer, "getLatency")
            ?: FabricCompat.readField(serverPlayer, "pingMilliseconds", "latency", "ping")
            ?: return null
        return (value as? Number)?.toInt()?.takeIf { it >= 0 }
    }

    override fun currentWorld(): String? {
        val world = FabricCompat.invokeNoArg(serverPlayer, "getWorld", "getServerWorld", "level")
            ?: FabricCompat.readField(serverPlayer, "world", "level")
            ?: return null

        // The registry key is the stable identifier; its value renders as
        // "minecraft:overworld", which is what the dashboard shows.
        val registryKey = FabricCompat.invokeNoArg(world, "getRegistryKey", "dimension")
            ?: return null
        val identifier = FabricCompat.invokeNoArg(registryKey, "getValue", "location")
            ?: return null
        return identifier.toString().takeIf { it.isNotBlank() }
    }

    /**
     * Records this sample's position and returns when the player last moved.
     * The observation and the query are the same call because Fabric gives this
     * module no movement event to observe from.
     */
    override fun getLastActivityMillis(): Long? {
        val id = readUuid() ?: return null
        val x = readCoordinate("getX", "x") ?: return activityTracker.lastActivityMillis(id)
        val y = readCoordinate("getY", "y") ?: return activityTracker.lastActivityMillis(id)
        val z = readCoordinate("getZ", "z") ?: return activityTracker.lastActivityMillis(id)
        return activityTracker.observe(id, x, y, z)
    }

    private fun readCoordinate(methodName: String, fieldName: String): Double? {
        val value = FabricCompat.invokeNoArg(serverPlayer, methodName)
            ?: FabricCompat.readField(serverPlayer, fieldName)
            ?: return null
        return (value as? Number)?.toDouble()
    }

    private fun readUuid(): String? {
        val value = FabricCompat.invokeNoArg(serverPlayer, "getUuid", "getUUID")
            ?: FabricCompat.readField(serverPlayer, "uuid")
            ?: return null
        return value.toString().takeIf { it.isNotBlank() }
    }

    private fun readName(): String? {
        val profile = FabricCompat.invokeNoArg(serverPlayer, "getGameProfile")
        if (profile != null) {
            val profileName = FabricCompat.invokeNoArg(profile, "getName")
            if (profileName is String && profileName.isNotBlank()) return profileName
        }

        val nameValue = FabricCompat.invokeNoArg(serverPlayer, "getEntityName", "getName")
        return nameValue?.toString()?.takeIf { it.isNotBlank() }
    }
}
