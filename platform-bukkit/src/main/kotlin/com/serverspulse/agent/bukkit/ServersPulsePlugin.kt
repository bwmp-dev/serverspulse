package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Bukkit/Spigot/Paper entry point for the ServersPulse agent.
 * This class is as thin as possible - it bootstraps the platform adapter
 * and hands control to the shared [AgentRuntime].
 *
 * Commands are delegated to [AgentRuntime.commandHandler] which lives in
 * agent-core, so all platforms share the same command logic.
 */
class ServersPulsePlugin : JavaPlugin(), CommandExecutor {

    private companion object {
        /** Data folder used before the plugin name was aligned with `serverspulse`. */
        const val LEGACY_DATA_FOLDER = "ServerPulse"
    }

    private lateinit var runtime: AgentRuntime

    override fun onEnable() {
        migrateLegacyDataFolder()

        val schedulerAdapter = BukkitSchedulerAdapter(this)
        val platformAdapter = BukkitPlatformAdapter(this, schedulerAdapter)

        val consoleRunner = object : ConsoleCommandRunner {
            override fun run(command: String) {
                schedulerAdapter.runPrimary(Runnable {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                })
            }
        }

        runtime = AgentRuntime(platformAdapter, consoleRunner)

        // Wire the /serverspulse command (declared in plugin.yml) to the shared handler
        getCommand("serverspulse")?.setExecutor(this)

        // Register player join/leave event listener
        server.pluginManager.registerEvents(
            BukkitPlayerListener(
                runtime,
                schedulerAdapter,
                platformAdapter.capabilities,
                platformAdapter.activityTracker
            ),
            this
        )

        // Movement and interaction feed AFK detection; the adapter owns the
        // tracker so the session sampler and these events see the same state.
        server.pluginManager.registerEvents(platformAdapter.activityTracker, this)

        runtime.start()
    }

    override fun onDisable() {
        if (::runtime.isInitialized) {
            runtime.stop()
        }
    }

    override fun onCommand(
        sender: org.bukkit.command.CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): Boolean {
        return runtime.commandHandler.execute(BukkitCommandSender(sender), args)
    }

    /**
     * Installs from before this rename kept their config in plugins/ServerPulse/, before
     * the plugin name was aligned with the `serverspulse` identifier the mod
     * platforms use. Move that folder across on first start, otherwise an upgrade
     * silently loses the saved API key and re-registers as a brand new server.
     *
     * Only runs when the new folder is absent or empty, so it can never clobber a
     * config the operator has already written.
     */
    private fun migrateLegacyDataFolder() {
        val target = dataFolder
        if (target.isDirectory && !target.list().isNullOrEmpty()) return

        val legacy = File(target.parentFile, LEGACY_DATA_FOLDER)
        if (!legacy.isDirectory) return

        if (target.exists() && !target.delete()) {
            logger.warning(
                "Cannot migrate plugins/$LEGACY_DATA_FOLDER/: ${target.name}/ is in the way " +
                    "and could not be removed. Move the files across manually."
            )
            return
        }

        if (legacy.renameTo(target)) {
            logger.info("Migrated config from plugins/$LEGACY_DATA_FOLDER/ to plugins/${target.name}/.")
        } else {
            logger.warning(
                "Could not move plugins/$LEGACY_DATA_FOLDER/ to plugins/${target.name}/. " +
                    "Copy it across manually to keep your existing API key."
            )
        }
    }

    /**
     * Wraps Bukkit's CommandSender into the platform-agnostic abstraction.
     */
    private class BukkitCommandSender(
        private val sender: org.bukkit.command.CommandSender
    ) : CommandSender {

        override fun sendMessage(message: String) {
            sender.sendMessage(message)
        }

        override fun hasPermission(permission: String): Boolean {
            return sender.hasPermission(permission)
        }

        override val isConsole: Boolean
            get() = sender is org.bukkit.command.ConsoleCommandSender

        override val isOp: Boolean
            get() = sender.isOp
    }
}
