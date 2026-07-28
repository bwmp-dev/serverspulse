package com.serverspulse.agent.fabric

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.collector.TickTimingTracker
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import net.fabricmc.api.DedicatedServerModInitializer
import net.minecraft.server.MinecraftServer
import org.apache.logging.log4j.LogManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fabric dedicated-server entrypoint for ServersPulse.
 * Hooks into lifecycle events, tick events, and registers Brigadier commands
 * directly against the server command dispatcher for broad version support.
 */
class FabricServersPulse : DedicatedServerModInitializer {

    private val logger = LogManager.getLogger("ServersPulse")
    private var runtime: AgentRuntime? = null
    private var adapter: FabricPlatformAdapter? = null
    private val tickTracker = TickTimingTracker()
    private val commandRegistered = AtomicBoolean(false)

    override fun onInitializeServer() {
        val commandCallbackHooked = registerCommandCallbacks()
        val tickHooked = registerTickHooks()
        val lifecycleHooked = registerLifecycleHooks()
        registerPlayerEventHooks()

        if (!lifecycleHooked) {
            bootstrapWithoutLifecycleEvents()
        }

        if (!commandCallbackHooked) {
            // Command callback APIs differ across Fabric versions; fallback paths retry direct registration.
        }

        if (!tickHooked) {
            // We can still run without lifecycle event hooks; TPS/MSPT will use fallback defaults.
        }
    }

    private fun startRuntime(server: MinecraftServer) {
        if (runtime != null) return

        registerCommands(server)

        val platformAdapter = FabricPlatformAdapter(server, tickTracker)
        this.adapter = platformAdapter

        val consoleRunner = object : ConsoleCommandRunner {
            override fun run(command: String) {
                server.execute {
                    executeServerCommand(server, command)
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
        commandRegistered.set(false)
    }

    private fun registerCommands(server: MinecraftServer): Boolean {
        if (commandRegistered.get()) return true

        val dispatcher = FabricCompat.findCommandDispatcher(server) ?: return false

        registerCommandTree(dispatcher)

        commandRegistered.set(true)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerCommandTree(dispatcher: CommandDispatcher<Any>) {
        registerCommandAlias(dispatcher, "serverspulse")
        registerCommandAlias(dispatcher, "sp")
        registerCommandAlias(dispatcher, "pulse")
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerCommandAlias(dispatcher: CommandDispatcher<Any>, rootLiteral: String) {
        if (dispatcher.root.getChild(rootLiteral) != null) {
            return
        }

        dispatcher.register(
            LiteralArgumentBuilder.literal<Any>(rootLiteral)
                .executes { ctx ->
                    executeCommand(ctx.source, emptyArray())
                    1
                }
                .then(
                    LiteralArgumentBuilder.literal<Any>("reload").executes { ctx ->
                        executeCommand(ctx.source, arrayOf("reload"))
                        1
                    }
                )
                .then(
                    LiteralArgumentBuilder.literal<Any>("status").executes { ctx ->
                        executeCommand(ctx.source, arrayOf("status"))
                        1
                    }
                )
                .then(
                    LiteralArgumentBuilder.literal<Any>("register")
                        .then(
                            RequiredArgumentBuilder.argument<Any, String>("code", StringArgumentType.string())
                                .executes { ctx ->
                                    val code = StringArgumentType.getString(ctx, "code")
                                    executeCommand(ctx.source, arrayOf("register", code))
                                    1
                                }
                        )
                )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeServerCommand(server: MinecraftServer, command: String) {
        val commandManager = FabricCompat.invokeNoArg(server, "getCommandManager") ?: return
        val source = FabricCompat.invokeNoArg(server, "getCommandSource") ?: return

        if (FabricCompat.invokeIfPresent(commandManager, "executeWithPrefix", source, command)) {
            return
        }
        if (FabricCompat.invokeIfPresent(commandManager, "execute", source, command)) {
            return
        }

        val dispatcher = FabricCompat.invokeNoArg(commandManager, "getDispatcher") as? CommandDispatcher<Any> ?: return
        dispatcher.execute(command, source)
    }

    private fun registerTickHooks(): Boolean {
        val startRegistered = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
            fieldName = "START_SERVER_TICK",
            callbackClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents\$StartTick"
        ) { _ -> tickTracker.onTickStart() }

        val endRegistered = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents",
            fieldName = "END_SERVER_TICK",
            callbackClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents\$EndTick"
        ) { _ -> tickTracker.onTickEnd() }

        return startRegistered && endRegistered
    }

    private fun registerCommandCallbacks(): Boolean {
        val callbackV2 = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
            fieldName = "EVENT",
            callbackClassName = "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback"
        ) { args ->
            val dispatcher = args.firstOrNull() as? CommandDispatcher<Any> ?: return@registerEventCallback
            registerCommandTree(dispatcher)
            commandRegistered.set(true)
        }

        val callbackV1 = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback",
            fieldName = "EVENT",
            callbackClassName = "net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback"
        ) { args ->
            val dispatcher = args.firstOrNull() as? CommandDispatcher<Any> ?: return@registerEventCallback
            registerCommandTree(dispatcher)
            commandRegistered.set(true)
        }

        return callbackV1 || callbackV2
    }

    private fun registerLifecycleHooks(): Boolean {
        val started = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            fieldName = "SERVER_STARTED",
            callbackClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents\$ServerStarted"
        ) { args ->
            val server = args.firstOrNull() as? MinecraftServer ?: return@registerEventCallback
            startRuntime(server)
            if (!registerCommands(server)) {
                retryCommandRegistration(server)
            }
        }

        val stopping = FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
            fieldName = "SERVER_STOPPING",
            callbackClassName = "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents\$ServerStopping"
        ) { _ ->
            stopRuntime()
        }

        return started && stopping
    }

    private fun bootstrapWithoutLifecycleEvents() {
        val bootstrapOnce: () -> Boolean = {
            val server = FabricCompat.currentServerFromLoader() as? MinecraftServer
            if (server == null) {
                false
            } else {
                startRuntime(server)
                if (!registerCommands(server)) {
                    retryCommandRegistration(server)
                }
                true
            }
        }

        if (!bootstrapOnce()) {
            val bootstrapThread = Thread({
                repeat(120) {
                    if (bootstrapOnce()) return@Thread
                    Thread.sleep(1000L)
                }
            }, "ServersPulse-Fabric-Bootstrap")
            bootstrapThread.isDaemon = true
            bootstrapThread.start()
        }

        Runtime.getRuntime().addShutdownHook(Thread({ stopRuntime() }, "ServersPulse-Fabric-Shutdown"))
    }

    /**
     * Registers player join/disconnect event callbacks via the Fabric networking API.
     * Uses the same reflection-based [FabricCompat.registerEventCallback] pattern as
     * lifecycle and tick hooks for broad version compatibility.
     * Hostname is not available at play-phase in Fabric, so null is passed.
     */
    private fun registerPlayerEventHooks() {
        FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
            fieldName = "JOIN",
            callbackClassName = "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents\$Join"
        ) { args ->
            val handler = args.firstOrNull() ?: return@registerEventCallback
            val (uuid, name) = extractPlayerIdentity(handler) ?: return@registerEventCallback
            runtime?.let { rt ->
                Thread {
                    rt.onPlayerJoin(uuid, name, null)
                }.also { it.isDaemon = true }.start()
            }
        }

        FabricCompat.registerEventCallback(
            ownerClassName = "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
            fieldName = "DISCONNECT",
            callbackClassName = "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents\$Disconnect"
        ) { args ->
            val handler = args.firstOrNull() ?: return@registerEventCallback
            val (uuid, name) = extractPlayerIdentity(handler) ?: return@registerEventCallback
            runtime?.let { rt ->
                Thread {
                    rt.onPlayerLeave(uuid, name)
                }.also { it.isDaemon = true }.start()
            }
        }
    }

    /**
     * Extracts the UUID and username from a `ServerPlayNetworkHandler` via reflection.
     * Returns null if the player cannot be resolved (should never happen in practice).
     */
    private fun extractPlayerIdentity(handler: Any): Pair<String, String>? {
        // handler.player or handler.getPlayer()
        val player = FabricCompat.readField(handler, "player")
            ?: FabricCompat.invokeNoArg(handler, "getPlayer")
            ?: return null

        // UUID: player.getUuid() returns java.util.UUID
        val uuid = (FabricCompat.invokeNoArg(player, "getUuid") as? java.util.UUID)?.toString()
            ?: return null

        // Username: prefer GameProfile.getName() for accuracy across versions
        val profile = FabricCompat.invokeNoArg(player, "getGameProfile")
        val name: String? = if (profile != null) {
            FabricCompat.invokeNoArg(profile, "getName") as? String
        } else {
            // Fallback: player.getName() returns a Text object; extract string from it
            val nameText = FabricCompat.invokeNoArg(player, "getName", "getEntityName")
            (FabricCompat.invokeNoArg(nameText ?: player, "getString", "getContents", "asString") as? String)
                ?: nameText?.toString()
        }

        return if (name.isNullOrBlank()) null else Pair(uuid, name)
    }

    private fun retryCommandRegistration(server: MinecraftServer) {
        if (commandRegistered.get()) return

        val retryThread = Thread({
            repeat(120) {
                if (commandRegistered.get()) return@Thread
                try {
                    server.execute { registerCommands(server) }
                } catch (_: Throwable) {
                }
                Thread.sleep(1000L)
            }
        }, "ServersPulse-Fabric-CommandRetry")
        retryThread.isDaemon = true
        retryThread.start()
    }

    private fun executeCommand(source: Any, args: Array<String>) {
        val handler = runtime?.commandHandler ?: run {
            FabricCompat.sendFeedback(source, "[ServersPulse] Agent not running.")
            return
        }
        handler.execute(FabricCommandSender(source), args)
    }

    /**
     * Adapts Fabric command sources to the platform-agnostic [CommandSender].
     */
    private inner class FabricCommandSender(
        private val source: Any
    ) : CommandSender {

        override fun sendMessage(message: String) {
            FabricCompat.sendFeedback(source, message)
            logger.info(message)
        }

        override fun hasPermission(permission: String): Boolean {
            return FabricCompat.invokeBoolean(source, "hasPermissionLevel", 4)
                ?: FabricCompat.invokeBoolean(source, "hasPermission", 4)
                ?: FabricCompat.invokeBoolean(source, "hasPermissions", 4)
                ?: false
        }

        override val isConsole: Boolean
            get() = (FabricCompat.invokeNoArg(source, "getEntity") ?: FabricCompat.readField(source, "entity")) == null

        override val isOp: Boolean
            get() = hasPermission("serverspulse.admin")
    }
}
