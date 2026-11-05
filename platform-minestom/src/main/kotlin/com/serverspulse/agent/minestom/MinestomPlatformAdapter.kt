package com.serverspulse.agent.minestom

import com.serverspulse.agent.api.*
import com.serverspulse.agent.core.collector.TickTimingTracker
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule
import java.io.File
import java.util.concurrent.ExecutorService

internal class MinestomPlatformAdapter(
    private val options: ServersPulseMinestom.Options,
    private val executor: ExecutorService,
    val timing: TickTimingTracker = TickTimingTracker()
) : PlatformAdapter {
    override val platformId = "minestom"
    override val mcVersion = "26.2"
    override val capabilities = object : VersionCapabilities {
        override val supportsMspt = true
        override val supportsNativeTickTimes = false
        override val supportsAsyncChunkStats = false
        override val supportsTpsApi = false
        override val supportsPlayerPing = true
        override val supportsPlayerWorld = true
    }
    private val scheduler = object : SchedulerAdapter {
        override fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle {
            val schedule = TaskSchedule.tick(intervalTicks.toInt())
            val handle = MinecraftServer.getSchedulerManager().scheduleTask(task, schedule, schedule)
            return object : TaskHandle { override fun cancel() = handle.cancel() }
        }
        override fun runAsync(task: Runnable) { executor.execute(task) }
    }
    override fun scheduler(): SchedulerAdapter = scheduler
    override fun server(): ServerIntrospection = object : ServerIntrospection {
        // Minestom's target tick rate is configurable, so a derived capped-at-20 TPS is misleading.
        override fun getTps(): Double? = null
        override fun getMspt(): Double = timing.getMspt()
        override fun getOnlinePlayers(): Int = MinecraftServer.getConnectionManager().onlinePlayers.size
        override fun getMaxPlayers(): Int = options.maxPlayers
    }
    override fun worlds(): List<WorldIntrospection> = MinecraftServer.getInstanceManager().instances.map { instance ->
        object : WorldIntrospection {
            override val name = instance.uuid.toString()
            override val dimension = instance.dimensionType.key().asString()
            override fun getEntityCount() = instance.entities.size
            override fun getLoadedChunks() = instance.chunks.size
            override fun getPlayerCount() = instance.players.size
        }
    }
    override fun players(): List<PlayerIntrospection> = MinecraftServer.getConnectionManager().onlinePlayers.map { player ->
        object : PlayerIntrospection {
            override val uuid = player.uuid.toString()
            override val name = player.username
            override fun getPing(): Int = player.latency
            override fun currentWorld(): String? = player.instance?.uuid?.toString()
        }
    }
    override fun logger(): LoggerAdapter = object : LoggerAdapter {
        private val log = java.util.logging.Logger.getLogger("ServersPulse")
        override fun info(message: String) = log.info(message)
        override fun warn(message: String) = log.warning(message)
        override fun error(message: String) = log.severe(message)
        override fun error(message: String, throwable: Throwable) = log.log(java.util.logging.Level.SEVERE, message, throwable)
        override fun debug(message: String) = log.fine(message)
    }
    override fun registerCommands(registry: CommandRegistry) = Unit
    override fun dataFolder(): File = options.dataFolder
}
