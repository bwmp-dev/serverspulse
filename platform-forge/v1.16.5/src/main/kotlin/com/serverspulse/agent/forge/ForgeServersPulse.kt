package com.serverspulse.agent.forge

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.collector.TickTimingTracker
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.command.CommandSource
import net.minecraft.command.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.util.text.StringTextComponent
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.server.FMLServerStartedEvent
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager

/**
 * Forge mod entrypoint for ServersPulse (MC 1.16.5, Forge 36.x).
 *
 * MC 1.16 uses [CommandSource] (not [net.minecraft.commands.CommandSourceStack]),
 * [StringTextComponent] (not [net.minecraft.network.chat.Component]), and
 * [net.minecraft.command.Commands] (not [net.minecraft.commands.Commands]).
 *
 * Event subscription uses the familiar [@Mod.EventBusSubscriber] + [@SubscribeEvent] pattern
 * which has been stable since at least Forge 36.x.
 *
 * Tick events use the single [TickEvent.ServerTickEvent] with a [TickEvent.Phase] field.
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
            else                  -> { /* ignore */ }
        }
    }

    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    fun onServerStarted(event: FMLServerStartedEvent) {
        startRuntime(event.server)
    }

    fun onServerStopping(@Suppress("UNUSED_PARAMETER") event: FMLServerStoppingEvent) {
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

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSource>) {
        registerCommandAlias(dispatcher, "serverspulse")
        registerCommandAlias(dispatcher, "sp")
        registerCommandAlias(dispatcher, "pulse")
    }

    private fun registerCommandAlias(dispatcher: CommandDispatcher<CommandSource>, rootLiteral: String) {
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
                    server.commands.performCommand(
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

    private fun executeCommand(source: CommandSource, args: Array<String>) {
        val handler = runtime?.commandHandler ?: run {
            source.sendSuccess(StringTextComponent("[ServersPulse] Agent not running."), false)
            return
        }
        handler.execute(ForgeCommandSender(source), args)
    }

    private class ForgeCommandSender(
        private val source: CommandSource
    ) : CommandSender {

        override fun sendMessage(message: String) {
            source.sendSuccess(StringTextComponent(message), false)
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
        fun onServerStarted(event: FMLServerStartedEvent) {
            instance?.onServerStarted(event)
        }

        @JvmStatic
        @SubscribeEvent
        fun onServerStopping(event: FMLServerStoppingEvent) {
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
