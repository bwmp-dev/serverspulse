package com.serverspulse.agent.minestom

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import java.io.File
import java.util.concurrent.Executors

class ServersPulseMinestom private constructor(options: Options) : AutoCloseable {
    class Options @JvmOverloads constructor(
        val dataFolder: File = File("serverspulse"),
        val maxPlayers: Int = 0
    ) {
        init { require(maxPlayers >= 0) }
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "serverspulse-minestom").apply { isDaemon = true }
    }
    private val platform = MinestomPlatformAdapter(options, executor)
    private val runtime = AgentRuntime(platform, object : ConsoleCommandRunner {
        override fun run(command: String) {
            MinecraftServer.getSchedulerManager().scheduleNextTick {
                MinecraftServer.getCommandManager().executeServerCommand(command)
            }
        }
    })
    private val events = EventNode.all("serverspulse")
    private var stopped = false

    init {
        events.addListener(ServerTickMonitorEvent::class.java) { event ->
            platform.timing.recordTickDurationNanos((event.tickMonitor.tickTime * 1_000_000).toLong())
        }
        events.addListener(PlayerSpawnEvent::class.java) { event ->
            if (event.isFirstSpawn) runtime.onPlayerJoin(event.player.uuid.toString(), event.player.username, null)
        }
        events.addListener(PlayerDisconnectEvent::class.java) { event ->
            runtime.onPlayerLeave(event.player.uuid.toString(), event.player.username)
        }
        MinecraftServer.getGlobalEventHandler().addChild(events)
        try { runtime.start() } catch (error: Exception) { stop(); throw error }
    }

    /** Optional console hook. Call with arguments following the serverspulse command label. */
    fun executeConsoleCommand(args: Array<String>): Boolean = runtime.commandHandler.execute(object : CommandSender {
        override val isConsole = true
        override val isOp = true
        override fun hasPermission(permission: String) = true
        override fun sendMessage(message: String) = platform.logger().info(message)
    }, args)

    @Synchronized fun stop() {
        if (stopped) return
        stopped = true
        MinecraftServer.getGlobalEventHandler().removeChild(events)
        try { runtime.stop() } finally { executor.shutdown() }
    }
    override fun close() = stop()

    companion object {
        /** Call after MinecraftServer.init(); close before MinecraftServer.stopCleanly(). */
        @JvmStatic @JvmOverloads fun start(options: Options = Options()): ServersPulseMinestom = ServersPulseMinestom(options)
    }
}
