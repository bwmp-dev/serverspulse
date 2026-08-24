package com.serverspulse.agent.fabric

import com.serverspulse.agent.api.*
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import java.io.File

/**
 * Top-level [PlatformAdapter] for Fabric servers.
 */
class FabricPlatformAdapter(
    private val server: MinecraftServer,
    val tickTracker: TickTimingTracker
) : PlatformAdapter {

    private val loggerAdapter = FabricLoggerAdapter()
    private val schedulerAdapter = FabricSchedulerAdapter(server)
    private val serverIntrospection = FabricServerIntrospection(server, tickTracker)
    private val resolvedMcVersion: String = detectMcVersion()

    override val platformId: String = "Fabric"

    override val mcVersion: String
        get() = resolvedMcVersion

    private val activityTracker = FabricPlayerActivityTracker()

    override val capabilities: VersionCapabilities = object : VersionCapabilities {
        override val supportsMspt: Boolean = true
        override val supportsNativeTickTimes: Boolean = true
        override val supportsAsyncChunkStats: Boolean = false
        override val supportsTpsApi: Boolean = true
        override val supportsPlayerPing: Boolean = true
        override val supportsPlayerWorld: Boolean = true
        // Position sampling stands in for a movement event, which is coarse but
        // real; the brand channel and handshake protocol need a mixin into the
        // connection handler and are honestly reported as unavailable.
        override val supportsActivityTracking: Boolean = true
        override val supportsClientBrand: Boolean = false
        override val supportsProtocolVersion: Boolean = false
        override val supportsPlayerAddress: Boolean = true
    }

    override fun scheduler(): SchedulerAdapter = schedulerAdapter

    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> {
        val worldContainer = FabricCompat.invokeNoArg(server, "getWorlds")
            ?: FabricCompat.readField(server, "worlds")
            ?: return emptyList()

        val worlds = when (worldContainer) {
            is Iterable<*> -> worldContainer.toList()
            is Map<*, *> -> worldContainer.values.toList()
            else -> emptyList()
        }

        return worlds.filterIsInstance<ServerWorld>().map { FabricWorldIntrospection(it) }
    }

    override fun players(): List<PlayerIntrospection> {
        val playerManager = FabricCompat.invokeNoArg(server, "getPlayerManager")
            ?: FabricCompat.readField(server, "playerManager")
            ?: return emptyList()

        val players = FabricCompat.invokeNoArg(playerManager, "getPlayerList") as? Collection<*>
            ?: FabricCompat.readField(playerManager, "playerList", "players") as? Collection<*>
            ?: return emptyList()

        val introspections = players.filterNotNull().map { FabricPlayerIntrospection(it, activityTracker) }
        activityTracker.retain(introspections.mapTo(HashSet()) { it.uuid })
        return introspections
    }

    override fun logger(): LoggerAdapter = loggerAdapter

    override fun registerCommands(registry: CommandRegistry) {
        // Commands are registered directly from FabricServersPulse
    }

    override fun dataFolder(): File {
        val folder = File("config/serverspulse")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun shutdownScheduler() {
        schedulerAdapter.shutdown()
    }

    private fun detectMcVersion(): String {
        return try {
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")
        } catch (e: Exception) {
            "unknown"
        }
    }
}
