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
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier

/**
 * Forge mod entrypoint for ServersPulse (MC 1.21.11, Forge 61.x).
 *
 * Forge 61.x introduces record-based events, each with a static [net.minecraftforge.eventbus.api.bus.EventBus]
 * field named [BUS] on the event class itself.  Registration is done via
 * [BUS.addListener()] in the mod constructor rather than via
 * [net.minecraftforge.common.MinecraftForge.EVENT_BUS] or [@Mod.EventBusSubscriber].
 *
 * [TickEvent] is a sealed interface in 61.x; only the concrete
 * [TickEvent.ServerTickEvent.Pre] and [TickEvent.ServerTickEvent.Post] subclasses
 * are fired and carry a [BUS].
 *
 * [ServerStartedEvent], [ServerStoppingEvent], and [RegisterCommandsEvent] are
 * all [record] types; access members via their accessor methods (e.g.
 * [ServerStartedEvent.getServer]).
 */
@Mod("serverspulse")
class ForgeServersPulse {

    private val logger = LogManager.getLogger("ServersPulse")
    val tickTracker = TickTimingTracker()

    var runtime: AgentRuntime? = null
    var adapter: ForgePlatformAdapter? = null
    private val commandsRegistered = AtomicBoolean(false)

    init {
        instance = this

        TickEvent.ServerTickEvent.Pre.BUS.addListener(::onServerTickPre)
        TickEvent.ServerTickEvent.Post.BUS.addListener(::onServerTickPost)
        ServerStartedEvent.BUS.addListener(::onServerStarted)
        ServerStoppingEvent.BUS.addListener(::onServerStopping)
        RegisterCommandsEvent.BUS.addListener(::onRegisterCommands)
        PlayerLoggedInEvent.BUS.addListener(::onPlayerJoin)
        PlayerLoggedOutEvent.BUS.addListener(::onPlayerLeave)
    }

    private fun onServerTickPre(@Suppress("UNUSED_PARAMETER") event: TickEvent.ServerTickEvent.Pre) {
        tickTracker.onTickStart()
    }

    private fun onServerTickPost(@Suppress("UNUSED_PARAMETER") event: TickEvent.ServerTickEvent.Post) {
        tickTracker.onTickEnd()
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        startRuntime(event.server)
    }

    private fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: ServerStoppingEvent) {
        stopRuntime()
    }

    private fun onPlayerJoin(event: PlayerLoggedInEvent) {
        val (uuid, name) = extractPlayerIdentity(event.entity) ?: return
        val rt = runtime ?: return
        Thread { rt.onPlayerJoin(uuid, name, null) }.also { it.isDaemon = true }.start()
    }

    private fun onPlayerLeave(event: PlayerLoggedOutEvent) {
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

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        if (commandsRegistered.get()) {
            return
        }

        registerCommandAlias(dispatcher, "serverspulse")
        registerCommandAlias(dispatcher, "sp")
        registerCommandAlias(dispatcher, "pulse")

        commandsRegistered.set(true)
    }

    private fun registerCommandAlias(dispatcher: CommandDispatcher<CommandSourceStack>, rootLiteral: String) {
        if (dispatcher.root.getChild(rootLiteral) != null) {
            return
        }

        dispatcher.register(
            Commands.literal(rootLiteral)
                .requires { _ -> true }
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
        if (!commandsRegistered.get()) {
            registerCommands(server.commands.dispatcher)
        }

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
        val component = Component.literal(message)
        val sourceClass = source.javaClass

        val sendSuccess = sourceClass.methods.firstOrNull {
            it.name == "sendSuccess" && it.parameterCount == 2 && it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        if (sendSuccess != null) {
            val firstType = sendSuccess.parameterTypes[0]
            try {
                when {
                    Supplier::class.java.isAssignableFrom(firstType) -> {
                        sendSuccess.invoke(source, Supplier<Component> { component }, false)
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

    private inner class ForgeCommandSender(
        private val source: CommandSourceStack
    ) : CommandSender {

        override fun sendMessage(message: String) {
            sendCommandFeedback(source, message)
        }

        override fun hasPermission(permission: String): Boolean = hasPermissionLevel(source, 4)

        override val isConsole: Boolean get() = source.entity == null
        override val isOp: Boolean get() = hasPermissionLevel(source, 4)
    }

    private fun hasPermissionLevel(source: CommandSourceStack, level: Int): Boolean {
        return invokePermissionMethod(source, "hasPermission", level)
            ?: invokePermissionMethod(source, "hasPermissions", level)
            ?: invokePermissionMethod(source, "hasPermissionLevel", level)
            ?: false
    }

    private fun invokePermissionMethod(source: CommandSourceStack, methodName: String, level: Int): Boolean? {
        return try {
            val method = source.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return null
            method.invoke(source, level) as? Boolean
        } catch (_: Throwable) {
            null
        }
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

    companion object {
        @Volatile
        var instance: ForgeServersPulse? = null
            private set
    }

}
