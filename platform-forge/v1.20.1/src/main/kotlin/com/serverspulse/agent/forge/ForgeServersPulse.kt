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
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager
import java.util.function.Supplier

/**
 * Forge mod entrypoint for ServersPulse (MC 1.18.2 – 1.20.x).
 *
 * Game-side events (server lifecycle, tick, commands) are handled by the
 * companion [EventHandler] object via [Mod.EventBusSubscriber], which registers
 * static listeners on the Forge game bus without touching [net.minecraftforge.common.MinecraftForge.EVENT_BUS]
 * directly — making the JAR forward-compatible with Forge builds that removed
 * that field (1.21+).
 */
@Mod("serverspulse")
class ForgeServersPulse {

    private val logger = LogManager.getLogger("ServersPulse")
    val tickTracker = TickTimingTracker()

    var runtime: AgentRuntime? = null
    var adapter: ForgePlatformAdapter? = null

    fun onServerTick(event: TickEvent.ServerTickEvent) {
        when (event.phase) {
            TickEvent.Phase.START -> tickTracker.onTickStart()
            TickEvent.Phase.END   -> tickTracker.onTickEnd()
        }
    }

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    fun onServerStarted(event: ServerStartedEvent) {
        startRuntime(event.server)
    }

    fun onServerStopping(event: ServerStoppingEvent) {
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
            sendCommandFeedback(source, "[ServersPulse] Agent not running.")
            return
        }
        handler.execute(ForgeCommandSender(source), args)
    }

    private fun sendCommandFeedback(source: CommandSourceStack, message: String) {
        val component = createLiteralComponent(source, message)
        if (component == null) {
            logger.info(message)
            return
        }

        val sourceClass = source.javaClass
        val sendSuccess = sourceClass.methods.firstOrNull {
            it.name == "sendSuccess" && it.parameterCount == 2 && it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        if (sendSuccess != null) {
            val firstType = sendSuccess.parameterTypes[0]
            try {
                when {
                    Supplier::class.java.isAssignableFrom(firstType) -> {
                        sendSuccess.invoke(source, Supplier<Any> { component }, false)
                        logger.info(message)
                        return
                    }

                    firstType.isAssignableFrom(component.javaClass) -> {
                        sendSuccess.invoke(source, component, false)
                        logger.info(message)
                        return
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val sendSystemMessage = sourceClass.methods.firstOrNull {
            it.name == "sendSystemMessage" && it.parameterCount == 1
        }
        if (sendSystemMessage != null) {
            try {
                sendSystemMessage.invoke(source, component)
                logger.info(message)
                return
            } catch (_: Throwable) {
            }
        }

        logger.info(message)
    }

    private fun createLiteralComponent(source: CommandSourceStack, message: String): Any? {
        val componentClass = source.javaClass.classLoader.loadClass("net.minecraft.network.chat.Component")

        val literalFactory = componentClass.methods.firstOrNull {
            java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java &&
                componentClass.isAssignableFrom(it.returnType)
        }
        if (literalFactory != null) {
            try {
                return literalFactory.invoke(null, message)
            } catch (_: Throwable) {
            }
        }

        return try {
            val textComponentClass = source.javaClass.classLoader.loadClass("net.minecraft.network.chat.TextComponent")
            textComponentClass.getConstructor(String::class.java).newInstance(message)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Adapts Forge's [CommandSourceStack] to the platform-agnostic [CommandSender].
     */
    private inner class ForgeCommandSender(
        private val source: CommandSourceStack
    ) : CommandSender {

        override fun sendMessage(message: String) {
            sendCommandFeedback(source, message)
        }

        override fun hasPermission(permission: String): Boolean {
            return source.hasPermission(4)
        }

        override val isConsole: Boolean
            get() = source.entity == null

        override val isOp: Boolean
            get() = source.hasPermission(4)
    }

    companion object {
        /** Singleton set during mod construction; used by the static event handler. */
        @Volatile
        var instance: ForgeServersPulse? = null
            private set
    }

    init {
        instance = this
    }

    /**
     * Static event subscriber for all Forge game-bus events.
     *
     * Using [Mod.EventBusSubscriber] + [@JvmStatic][SubscribeEvent] avoids any
     * direct reference to [net.minecraftforge.common.MinecraftForge.EVENT_BUS],
     * which was removed in Forge 1.21.x.
     */
    @Mod.EventBusSubscriber(modid = "serverspulse", bus = Mod.EventBusSubscriber.Bus.FORGE)
    object EventHandler {

        @JvmStatic
        @SubscribeEvent
        fun onServerTick(event: TickEvent.ServerTickEvent) {
            instance?.onServerTick(event)
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
