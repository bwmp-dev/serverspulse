package com.serverspulse.agent.forge

import com.serverspulse.agent.api.*
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minecraft.server.MinecraftServer
import net.minecraftforge.fml.common.Loader
import java.io.File

/**
 * Top-level [PlatformAdapter] for Forge 1.12.2 servers.
 *
 * Version detection uses [Loader.MC_VERSION] which is a static constant available in 1.12.2.
 */
class ForgePlatformAdapter(
    private val server: MinecraftServer,
    val tickTracker: TickTimingTracker
) : PlatformAdapter {

    private val loggerAdapter = ForgeLoggerAdapter()
    private val schedulerAdapter = ForgeSchedulerAdapter(server)
    private val serverIntrospection = ForgeServerIntrospection(server, tickTracker)

    override val platformId: String = "Forge"

    override val mcVersion: String
        get() = Loader.MC_VERSION

    override val capabilities: VersionCapabilities = object : VersionCapabilities {
        override val supportsMspt: Boolean = true
        override val supportsNativeTickTimes: Boolean = true
        override val supportsAsyncChunkStats: Boolean = false
        override val supportsTpsApi: Boolean = true
    }

    override fun scheduler(): SchedulerAdapter = schedulerAdapter
    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> =
        server.worlds.map { ForgeWorldIntrospection(it) }

    override fun logger(): LoggerAdapter = loggerAdapter

    override fun registerCommands(registry: CommandRegistry) {
        // Commands registered via ICommand in ForgeServersPulse.startRuntime()
    }

    override fun dataFolder(): File {
        val folder = File("config/serverspulse")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun shutdownScheduler() {
        schedulerAdapter.shutdown()
    }
}
