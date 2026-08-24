package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.dto.PlayerSwitchPayload
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerSwitchEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BungeeCord and Waterfall entry point.
 *
 * One artifact covers both: they share this API, and [BungeePlatformAdapter]
 * tells them apart from the proxy name at runtime.
 */
class BungeeServersPulse : Plugin(), Listener {

    private companion object {
        const val PERMISSION = "serverspulse.admin"
    }

    private lateinit var schedulerAdapter: BungeeSchedulerAdapter
    private lateinit var runtime: AgentRuntime

    /**
     * The backend each player is currently on.
     *
     * [ServerSwitchEvent] fires after the move has happened and a disconnect
     * has to name the server the player left, so both answers come from what
     * was recorded on the previous switch.
     */
    private val currentServer = ConcurrentHashMap<UUID, String>()

    override fun onEnable() {
        schedulerAdapter = BungeeSchedulerAdapter(this)

        val platformAdapter = BungeePlatformAdapter(
            proxy = proxy,
            schedulerAdapter = schedulerAdapter,
            loggerAdapter = BungeeLoggerAdapter(logger),
            pluginDataFolder = dataFolder
        )

        val consoleRunner = object : ConsoleCommandRunner {
            override fun run(command: String) {
                proxy.pluginManager.dispatchCommand(proxy.console, command)
            }
        }

        runtime = AgentRuntime(platformAdapter, consoleRunner)

        proxy.pluginManager.registerListener(this, this)
        proxy.pluginManager.registerCommand(this, AgentCommand())

        runtime.start()
    }

    override fun onDisable() {
        if (::runtime.isInitialized) {
            runtime.stop()
        }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val username = player.name
        val hostname = player.pendingConnection.virtualHost?.hostString?.takeIf { it.isNotBlank() }
        val protocolVersion = player.pendingConnection.version.takeIf { it > 0 }
        // Handed to the runtime purely so it can derive a country code locally.
        // It is never sent, stored or logged; see AgentRuntime.onPlayerJoin.
        val address = (player.socketAddress as? InetSocketAddress)?.address

        schedulerAdapter.runAsync(Runnable {
            runtime.onPlayerJoin(
                playerUuid = uuid,
                username = username,
                hostname = hostname,
                protocolVersion = protocolVersion,
                address = address
            )
        })
    }

    @EventHandler
    fun onServerSwitch(event: ServerSwitchEvent) {
        val player = event.player
        val target = player.server?.info?.name ?: return
        val uuid = player.uniqueId
        val previous = currentServer.put(uuid, target)

        if (previous == target) return

        runtime.onPlayerSwitch(
            playerUuid = uuid.toString(),
            fromServer = previous,
            toServer = target,
            reason = if (previous == null) PlayerSwitchPayload.REASON_JOIN else PlayerSwitchPayload.REASON_SWITCH
        )
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val username = player.name
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

    private inner class AgentCommand : Command(BungeePlatformAdapter.COMMAND_LABEL, null, "spp", "spproxy") {
        override fun execute(sender: CommandSender, args: Array<String>) {
            runtime.commandHandler.execute(BungeeCommandSender(sender), args)
        }
    }

    /**
     * Bungee has no operator concept, so administrative access is the
     * permission node alone; the console is always allowed.
     */
    private class BungeeCommandSender(private val sender: CommandSender) :
        com.serverspulse.agent.api.CommandSender {

        override fun sendMessage(message: String) {
            sender.sendMessage(TextComponent(message))
        }

        override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

        override val isConsole: Boolean
            get() = sender !is ProxiedPlayer

        override val isOp: Boolean
            get() = sender.hasPermission(PERMISSION)
    }
}
