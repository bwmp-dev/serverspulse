package com.serverspulse.agent.neoforge

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.collector.TickTimingTracker
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.apache.logging.log4j.LogManager

/**
 * NeoForge mod entrypoint for ServersPulse (MC 1.21.1).
 * Registers event listeners for server lifecycle, tick events, and Brigadier commands.
 */
@Mod("serverspulse")
class NeoForgeServersPulse {

    private val logger = LogManager.getLogger("ServersPulse")
    private val tickTracker = TickTimingTracker()

    private var runtime: AgentRuntime? = null
    private var adapter: NeoForgePlatformAdapter? = null

    init {
        NeoForge.EVENT_BUS.register(this)
    }

    @SubscribeEvent
    fun onServerTickPre(event: ServerTickEvent.Pre) {
        tickTracker.onTickStart()
    }

    @SubscribeEvent
    fun onServerTickPost(event: ServerTickEvent.Post) {
        tickTracker.onTickEnd()
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        startRuntime(event.server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        stopRuntime()
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity
        val uuid = player.gameProfile.id?.toString() ?: return
        val name = player.gameProfile.name ?: return
        val rt = runtime ?: return
        Thread {
            // Virtual hostname (server address the player typed) is not
            // readily accessible at this event stage in NeoForge, so null is passed.
            rt.onPlayerJoin(uuid, name, null)
        }.also { it.isDaemon = true }.start()
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity
        val uuid = player.gameProfile.id?.toString() ?: return
        val name = player.gameProfile.name ?: return
        val rt = runtime ?: return
        Thread {
            rt.onPlayerLeave(uuid, name)
        }.also { it.isDaemon = true }.start()
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        registerCommandAlias(dispatcher, "serverspulse")
        registerCommandAlias(dispatcher, "sp")
        registerCommandAlias(dispatcher, "pulse")
    }

    private fun registerCommandAlias(dispatcher: CommandDispatcher<CommandSourceStack>, rootLiteral: String) {
        if (dispatcher.root.getChild(rootLiteral) != null) {
            return
        }

        dispatcher.register(
            Commands.literal(rootLiteral)
                .requires { source -> source.hasPermission(4) }
                .executes { ctx ->
                    executeCommand(ctx.source, arrayOf()); 1
                }
                .then(Commands.literal("reload").executes { ctx ->
                    executeCommand(ctx.source, arrayOf("reload")); 1
                })
                .then(Commands.literal("status").executes { ctx ->
                    executeCommand(ctx.source, arrayOf("status")); 1
                })
                .then(Commands.literal("register")
                    .then(Commands.argument("code", StringArgumentType.string())
                        .executes { ctx ->
                            val code = StringArgumentType.getString(ctx, "code")
                            executeCommand(ctx.source, arrayOf("register", code)); 1
                        }))
        )
    }

    private fun startRuntime(server: MinecraftServer) {
        val platformAdapter = NeoForgePlatformAdapter(server, tickTracker)
        this.adapter = platformAdapter

        val consoleRunner = object : ConsoleCommandRunner {
            override fun run(command: String) {
                server.execute {
                    server.commands.performPrefixedCommand(
                        server.createCommandSourceStack(), command
                    )
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

    private fun executeCommand(source: CommandSourceStack, args: Array<String>) {
        val handler = runtime?.commandHandler ?: run {
            source.sendSuccess({ Component.literal("[ServersPulse] Agent not running.") }, false)
            return
        }
        handler.execute(NeoForgeCommandSender(source), args)
    }

    /**
     * Adapts NeoForge's [CommandSourceStack] to the platform-agnostic [CommandSender].
     */
    private class NeoForgeCommandSender(
        private val source: CommandSourceStack
    ) : CommandSender {

        override fun sendMessage(message: String) {
            source.sendSuccess({ Component.literal(message) }, false)
        }

        override fun hasPermission(permission: String): Boolean {
            return source.hasPermission(4)
        }

        override val isConsole: Boolean
            get() = source.entity == null

        override val isOp: Boolean
            get() = source.hasPermission(4)
    }
}
