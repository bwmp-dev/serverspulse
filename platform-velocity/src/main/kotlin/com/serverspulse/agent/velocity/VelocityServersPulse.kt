package com.serverspulse.agent.velocity

import com.google.inject.Inject
import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.api.dto.PlayerSwitchPayload
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Velocity entry point.
 *
 * `velocity-plugin.json` in this module's resources is what the proxy actually
 * reads. The annotation below records the same identity for a reader but is
 * never processed: the annotation processor that would generate that file does
 * not run over Kotlin sources.
 */
@Plugin(id = "serverspulse", name = "ServersPulse", authors = ["bwmp"], url = "https://serverspulse.com")
class VelocityServersPulse @Inject constructor(
    private val proxy: ProxyServer,
    logger: Logger,
    @DataDirectory dataDirectory: Path
) {
    private companion object {
        /**
         * The client sends its brand shortly after login, so reading it in the
         * login handler returns nothing. Two seconds is late enough for it to
         * have arrived and early enough that the event still describes a join.
         */
        const val BRAND_READ_DELAY_MILLIS = 2000L
    }

    private val loggerAdapter = VelocityLoggerAdapter(logger)
    private val schedulerAdapter = VelocitySchedulerAdapter(proxy, this)
    private val platformAdapter =
        VelocityPlatformAdapter(proxy, schedulerAdapter, loggerAdapter, dataDirectory)

    private val consoleRunner = object : ConsoleCommandRunner {
        override fun run(command: String) {
            proxy.commandManager.executeAsync(proxy.consoleCommandSource, command)
        }
    }

    private val runtime = AgentRuntime(platformAdapter, consoleRunner)

    /**
     * The backend each player is currently on.
     *
     * A disconnect has to name the server the player left, and by the time
     * [DisconnectEvent] fires the connection that knew it is already gone.
     */
    private val currentServer = ConcurrentHashMap<UUID, String>()

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        proxy.commandManager.register(
            proxy.commandManager.metaBuilder(VelocityPlatformAdapter.COMMAND_LABEL)
                .aliases("spp", "spproxy")
                .plugin(this)
                .build(),
            AgentCommand()
        )
        runtime.start()
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        runtime.stop()
    }

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val username = player.username
        val hostname = player.virtualHost.map(InetSocketAddress::getHostString).orElse(null)
        // Handed to the runtime purely so it can derive a country code locally.
        // It is never sent, stored or logged; see AgentRuntime.onPlayerJoin.
        val address = player.remoteAddress.address

        schedulerAdapter.runLater(BRAND_READ_DELAY_MILLIS, Runnable {
            if (!player.isActive) return@Runnable
            val introspection = VelocityPlayerIntrospection(player)
            runtime.onPlayerJoin(
                playerUuid = uuid,
                username = username,
                hostname = hostname,
                clientBrand = introspection.getClientBrand(),
                protocolVersion = introspection.getProtocolVersion(),
                address = address
            )
        })
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val uuid = event.player.uniqueId
        val target = event.server.serverInfo.name
        // Empty on the first connect of a session, which is an arrival on the
        // network rather than a transition between backends.
        val previous = event.previousServer.map { it.serverInfo.name }.orElse(null)

        currentServer[uuid] = target
        runtime.onPlayerSwitch(
            playerUuid = uuid.toString(),
            fromServer = previous,
            toServer = target,
            reason = if (previous == null) PlayerSwitchPayload.REASON_JOIN else PlayerSwitchPayload.REASON_SWITCH
        )
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val username = player.username
        val from = currentServer.remove(uuid)

        if (from != null) {
            runtime.onPlayerSwitch(
                playerUuid = uuid.toString(),
                fromServer = from,
                toServer = null,
                reason = PlayerSwitchPayload.REASON_DISCONNECT
            )
        }

        schedulerAdapter.runAsync(Runnable {
            runtime.onPlayerLeave(uuid.toString(), username)
        })
    }

    private inner class AgentCommand : SimpleCommand {
        override fun execute(invocation: SimpleCommand.Invocation) {
            runtime.commandHandler.execute(VelocityCommandSender(invocation.source()), invocation.arguments())
        }

        override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
            return invocation.source() is ConsoleCommandSource ||
                invocation.source().hasPermission("serverspulse.admin")
        }
    }

    /**
     * Velocity has no operator concept, so administrative access is the
     * permission node alone; the console is always allowed.
     */
    private class VelocityCommandSender(private val source: CommandSource) : CommandSender {

        override fun sendMessage(message: String) {
            source.sendMessage(Component.text(message))
        }

        override fun hasPermission(permission: String): Boolean = source.hasPermission(permission)

        override val isConsole: Boolean
            get() = source is ConsoleCommandSource

        override val isOp: Boolean
            get() = source.hasPermission("serverspulse.admin")
    }
}
