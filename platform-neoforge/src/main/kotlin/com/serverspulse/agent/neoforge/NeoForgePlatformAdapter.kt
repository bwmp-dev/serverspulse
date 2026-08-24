package com.serverspulse.agent.neoforge

import com.serverspulse.agent.api.*
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.SharedConstants
import net.minecraft.server.MinecraftServer
import java.io.File

/**
 * Top-level [PlatformAdapter] for NeoForge servers.
 */
class NeoForgePlatformAdapter(
    private val server: MinecraftServer,
    val tickTracker: TickTimingTracker
) : PlatformAdapter {

    private val loggerAdapter = NeoForgeLoggerAdapter()
    private val schedulerAdapter = NeoForgeSchedulerAdapter(server)
    private val serverIntrospection = NeoForgeServerIntrospection(server, tickTracker)
    private val resolvedMcVersion: String = detectMcVersion()

    override val platformId: String = "NeoForge"

    override val mcVersion: String
        get() = resolvedMcVersion

    private val activityTracker = NeoForgePlayerActivityTracker()

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
        return server.allLevels.map { NeoForgeWorldIntrospection(it) }
    }

    override fun players(): List<PlayerIntrospection> {
        val introspections = server.playerList.players.map {
            NeoForgePlayerIntrospection(it, activityTracker)
        }
        activityTracker.retain(introspections.mapTo(HashSet()) { it.uuid })
        return introspections
    }

    override fun logger(): LoggerAdapter = loggerAdapter

    override fun registerCommands(registry: CommandRegistry) {
        // Commands registered via RegisterCommandsEvent in NeoForgeServersPulse
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
            SharedConstants.getCurrentVersion().name
        } catch (_: Throwable) {
            try {
                server.serverVersion
            } catch (_: Throwable) {
                "unknown"
            }
        }
    }
}
