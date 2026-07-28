package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.*
import com.serverspulse.agent.bukkit.version.VersionResolver
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Top-level [PlatformAdapter] implementation for Bukkit/Spigot/Paper servers.
 * Wires together all platform-specific adapters and exposes them to the core runtime.
 *
 * Supports MC 1.8.8+ by compiling against the 1.8.8 Spigot API and using
 * reflection for features added in later versions (TPS, MSPT, etc.).
 */
class BukkitPlatformAdapter(
    private val plugin: JavaPlugin,
    private val schedulerAdapter: BukkitSchedulerAdapter = BukkitSchedulerAdapter(plugin)
) : PlatformAdapter {

    private val serverInfo = VersionResolver.detectServer()
    private val resolvedCapabilities = VersionResolver.resolveCapabilities(serverInfo)
    private val loggerAdapter = BukkitLoggerAdapter(plugin.logger)

    // Start a tick counter as TPS fallback for servers without getTPS() (pre-1.9, CraftBukkit)
    private val tickCounter: TickCounter? =
        if (!resolvedCapabilities.supportsTpsApi) TickCounter(plugin) else null

    private val serverIntrospection = BukkitServerIntrospection(resolvedCapabilities, tickCounter)

    override val platformId: String
        get() = serverInfo.platformId

    override val mcVersion: String
        get() = serverInfo.mcVersion

    override val capabilities: VersionCapabilities
        get() = resolvedCapabilities

    override fun scheduler(): SchedulerAdapter = schedulerAdapter

    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> {
        return Bukkit.getWorlds().map { BukkitWorldIntrospection(it) }
    }

    override fun logger(): LoggerAdapter = loggerAdapter

    override fun registerCommands(registry: CommandRegistry) {
        // Commands are registered via the plugin entry point since
        // Bukkit requires commands to be declared in plugin.yml
        // and registered through the plugin instance
    }

    override fun dataFolder(): File = plugin.dataFolder
}
