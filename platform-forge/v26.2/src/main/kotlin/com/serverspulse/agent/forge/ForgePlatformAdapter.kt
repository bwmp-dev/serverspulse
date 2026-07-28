package com.serverspulse.agent.forge

import com.serverspulse.agent.api.*
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.SharedConstants
import net.minecraft.server.MinecraftServer
import java.io.File

/**
 * Top-level [PlatformAdapter] for Forge 26.2 / 65.x servers.
 */
class ForgePlatformAdapter(
    private val server: MinecraftServer,
    val tickTracker: TickTimingTracker
) : PlatformAdapter {

    private val loggerAdapter = ForgeLoggerAdapter()
    private val schedulerAdapter = ForgeSchedulerAdapter(server)
    private val serverIntrospection = ForgeServerIntrospection(server, tickTracker)
    private val resolvedMcVersion: String = detectMcVersion()

    override val platformId: String = "Forge"

    override val mcVersion: String
        get() = resolvedMcVersion

    override val capabilities: VersionCapabilities = object : VersionCapabilities {
        override val supportsMspt: Boolean = true
        override val supportsNativeTickTimes: Boolean = true
        override val supportsAsyncChunkStats: Boolean = false
        override val supportsTpsApi: Boolean = true
    }

    override fun scheduler(): SchedulerAdapter = schedulerAdapter
    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> =
        server.allLevels.map { ForgeWorldIntrospection(it) }

    override fun logger(): LoggerAdapter = loggerAdapter

    override fun registerCommands(registry: CommandRegistry) {
        // Commands registered via RegisterCommandsEvent in ForgeServersPulse
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
            // WorldVersion exposes `name()` in 26.x, not a bean-style getter.
            SharedConstants.getCurrentVersion().name()
        } catch (_: Throwable) {
            server.serverVersion.takeIf { it.isNotBlank() } ?: "unknown"
        }
    }
}
