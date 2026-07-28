package com.serverspulse.agent.forge

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.collector.TickTimingTracker
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import net.minecraft.command.ICommand
import net.minecraft.command.ICommandSender
import net.minecraft.server.MinecraftServer
import net.minecraft.util.text.TextComponentString
import net.minecraft.world.World
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLServerStartedEvent
import net.minecraftforge.fml.common.event.FMLServerStartingEvent
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent
import net.minecraftforge.fml.common.gameevent.TickEvent as GameTickEvent
import net.minecraftforge.fml.server.FMLServerHandler
import org.apache.logging.log4j.LogManager

/**
 * Forge mod entrypoint for ServersPulse (MC 1.12.2, Forge 14.23.x).
 *
 * 1.12.2 differences from later versions:
 * - No Brigadier; commands use [ICommand].
 * - Server lifecycle events come from the FML mod event bus ([FMLServerStartedEvent],
 *   [FMLServerStoppingEvent]) declared as methods annotated with [@Mod.EventHandler].
 * - Tick events are [net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent]
 *   and require manual registration on [MinecraftForge.EVENT_BUS].
 * - Text uses [TextComponentString] (not [net.minecraft.network.chat.Component]).
 */
@Mod(
    modid = "serverspulse",
    name = "ServersPulse",
    version = "@VERSION@",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.12,1.13)"
)
class ForgeServersPulse {

    private val logger = LogManager.getLogger("ServersPulse")
    val tickTracker = TickTimingTracker()

    var runtime: AgentRuntime? = null
    var adapter: ForgePlatformAdapter? = null

    init {
        instance = this
        MinecraftForge.EVENT_BUS.register(EventHandler)
    }

    @Mod.EventHandler
    fun onServerStarting(event: FMLServerStartingEvent) {
        event.registerServerCommand(ServersPulseCommand(this))
    }

    @Mod.EventHandler
    fun onServerStarted(event: FMLServerStartedEvent) {
        val server = FMLServerHandler.instance().server
        startRuntime(server)
    }

    @Mod.EventHandler
    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: FMLServerStoppingEvent) {
        stopRuntime()
    }

    private fun startRuntime(server: MinecraftServer) {
        val platformAdapter = ForgePlatformAdapter(server, tickTracker)
        this.adapter = platformAdapter

        val consoleRunner = object : ConsoleCommandRunner {
            override fun run(command: String) {
                server.addScheduledTask {
                    server.commandManager.executeCommand(server as ICommandSender, command)
                }
            }
        }

        val agentRuntime = AgentRuntime(platformAdapter, consoleRunner)
        this.runtime = agentRuntime
        agentRuntime.start()
    }

    private fun stopRuntime() {
        runtime?.stop()
        adapter?.shutdownScheduler()
        runtime = null
        adapter = null
    }

    fun onPlayerJoin(event: PlayerLoggedInEvent) {
        val uuid = event.player.gameProfile.id?.toString() ?: return
        val name = event.player.gameProfile.name ?: return
        val rt = runtime ?: return
        Thread { rt.onPlayerJoin(uuid, name, null) }.also { it.isDaemon = true }.start()
    }

    fun onPlayerLeave(event: PlayerLoggedOutEvent) {
        val uuid = event.player.gameProfile.id?.toString() ?: return
        val name = event.player.gameProfile.name ?: return
        val rt = runtime ?: return
        Thread { rt.onPlayerLeave(uuid, name) }.also { it.isDaemon = true }.start()
    }

    fun executeCommand(sender: ICommandSender, args: Array<String>) {
        val handler = runtime?.commandHandler ?: run {
            sender.sendMessage(TextComponentString("[ServersPulse] Agent not running."))
            return
        }
        handler.execute(LegacyCommandSender(sender), args)
    }

    private class LegacyCommandSender(
        private val sender: ICommandSender
    ) : CommandSender {

        override fun sendMessage(message: String) {
            sender.sendMessage(TextComponentString(message))
        }

        override fun hasPermission(permission: String): Boolean =
            sender.canUseCommand(4, "serverspulse")

        override val isConsole: Boolean
            get() = sender is MinecraftServer

        override val isOp: Boolean
            get() = sender.canUseCommand(4, "serverspulse")
    }

    companion object {
        @Volatile
        var instance: ForgeServersPulse? = null
            private set
    }

    object EventHandler {
        @SubscribeEvent
        fun onServerTick(event: GameTickEvent.ServerTickEvent) {
            val inst = instance ?: return
            when (event.phase) {
                GameTickEvent.Phase.START -> inst.tickTracker.onTickStart()
                GameTickEvent.Phase.END   -> inst.tickTracker.onTickEnd()
                else                  -> { /* ignore */ }
            }
        }

        @SubscribeEvent
        fun onPlayerJoin(event: PlayerLoggedInEvent) {
            instance?.onPlayerJoin(event)
        }

        @SubscribeEvent
        fun onPlayerLeave(event: PlayerLoggedOutEvent) {
            instance?.onPlayerLeave(event)
        }
    }
}
