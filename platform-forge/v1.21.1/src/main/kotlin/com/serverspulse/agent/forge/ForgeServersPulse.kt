package com.serverspulse.agent.forge

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
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager

/**
 * Forge mod entrypoint for ServersPulse (MC 1.21.1, Forge 61.x).
 *
 * In Forge 1.21.x [net.minecraftforge.common.MinecraftForge.EVENT_BUS] was
 * removed as a public field.  All game-bus events are subscribed via the
 * [Mod.EventBusSubscriber] annotation on [EventHandler], which Forge resolves
 * and registers automatically without any direct field reference.
 *
 * Tick events changed in 1.21.x: Forge no longer fires a single
 * [TickEvent.ServerTickEvent]; it fires [TickEvent.ServerTickEvent.Pre] and
 * [TickEvent.ServerTickEvent.Post] instead.  Subscribe to those subclasses
 * directly.
 */
@Mod("serverspulse")
class ForgeServersPulse {

    private val logger = LogManager.getLogger("ServersPulse")
    val tickTracker = TickTimingTracker()

    var runtime: AgentRuntime? = null
    var adapter: ForgePlatformAdapter? = null

    fun onServerTickPre(@Suppress("UNUSED_PARAMETER") event: TickEvent.ServerTickEvent.Pre) {
        tickTracker.onTickStart()
    }

    fun onServerTickPost(@Suppress("UNUSED_PARAMETER") event: TickEvent.ServerTickEvent.Post) {
        tickTracker.onTickEnd()
    }

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    fun onServerStarted(event: ServerStartedEvent) {
        startRuntime(event.server)
    }

    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        stopRuntime()
    }

    fun onPlayerJoin(event: PlayerLoggedInEvent) {
        val (uuid, name) = extractPlayerIdentity(event.entity) ?: return
        val rt = runtime ?: return
        Thread { rt.onPlayerJoin(uuid, name, null) }.also { it.isDaemon = true }.start()
    }

    fun onPlayerLeave(event: PlayerLoggedOutEvent) {
        val (uuid, name) = extractPlayerIdentity(event.entity) ?: return
        val rt = runtime ?: return
        Thread { rt.onPlayerLeave(uuid, name) }.also { it.isDaemon = true }.start()
    }

    private fun extractPlayerIdentity(player: Any): Pair<String, String>? {
        val uuid = (invokeNoArg(player, "getStringUUID") as? String)
            ?: (invokeNoArg(player, "getUUID") as? java.util.UUID)?.toString()
            ?: return null

        val name = (invokeNoArg(player, "getScoreboardName") as? String)
            ?: run {
                val component = invokeNoArg(player, "getName") ?: return@run null
                invokeNoArg(component, "getString") as? String
            }
            ?: return null

        return uuid to name
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return null
            method.invoke(target)
        } catch (_: Throwable) {
            null
        }
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
        val platformAdapter = ForgePlatformAdapter(server, tickTracker)
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
        handler.execute(ForgeCommandSender(source), args)
    }

    private class ForgeCommandSender(
        private val source: CommandSourceStack
    ) : CommandSender {

        override fun sendMessage(message: String) {
            source.sendSuccess({ Component.literal(message) }, false)
        }

        override fun hasPermission(permission: String): Boolean = source.hasPermission(4)

        override val isConsole: Boolean get() = source.entity == null
        override val isOp: Boolean get() = source.hasPermission(4)
    }

    companion object {
        @Volatile
        var instance: ForgeServersPulse? = null
            private set
    }

    init {
        instance = this
    }

    /**
     * Static subscriber for all Forge game-bus events (Forge 1.21.1 / 61.x).
     *
     * Uses [TickEvent.ServerTickEvent.Pre] / [TickEvent.ServerTickEvent.Post]
     * because Forge 1.21.x no longer fires the plain [TickEvent.ServerTickEvent]
     * base class directly.
     */
    @Mod.EventBusSubscriber(modid = "serverspulse", bus = Mod.EventBusSubscriber.Bus.FORGE)
    object EventHandler {

        @JvmStatic
        @SubscribeEvent
        fun onServerTickPre(event: TickEvent.ServerTickEvent.Pre) {
            instance?.onServerTickPre(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onServerTickPost(event: TickEvent.ServerTickEvent.Post) {
            instance?.onServerTickPost(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onRegisterCommands(event: RegisterCommandsEvent) {
            instance?.onRegisterCommands(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onServerStarted(event: ServerStartedEvent) {
            instance?.onServerStarted(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onServerStopping(event: ServerStoppingEvent) {
            instance?.onServerStopping(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onPlayerJoin(event: PlayerLoggedInEvent) {
            instance?.onPlayerJoin(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onPlayerLeave(event: PlayerLoggedOutEvent) {
            instance?.onPlayerLeave(event)
        }
    }
}
