package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.CommandRegistry
import com.serverspulse.agent.api.LoggerAdapter
import com.serverspulse.agent.api.PlatformAdapter
import com.serverspulse.agent.api.PlayerIntrospection
import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.api.VersionCapabilities
import com.serverspulse.agent.api.WorldIntrospection
import net.md_5.bungee.api.ProxyServer
import java.io.File

/**
 * Top-level [PlatformAdapter] for BungeeCord and Waterfall.
 *
 * A proxy is a normal platform to the core runtime, minus the two things it
 * genuinely does not have: a tick loop and worlds. Both are reported as absent
 * rather than filled in.
 */
class BungeePlatformAdapter(
    private val proxy: ProxyServer,
    private val schedulerAdapter: SchedulerAdapter,
    private val loggerAdapter: LoggerAdapter,
    private val pluginDataFolder: File
) : PlatformAdapter {

    private val serverIntrospection = BungeeServerIntrospection(proxy)

    /**
     * Waterfall is a BungeeCord fork with the same API, and the two are only
     * distinguishable at runtime. They are reported separately because their
     * release cadences and supported protocol ranges differ.
     */
    override val platformId: String =
        if (proxy.name.contains("waterfall", ignoreCase = true)) "waterfall" else "bungeecord"

    override val mcVersion: String
        get() = proxy.version

    override val capabilities: VersionCapabilities = object : VersionCapabilities {
        override val supportsMspt: Boolean = false
        override val supportsNativeTickTimes: Boolean = false
        override val supportsAsyncChunkStats: Boolean = false
        override val supportsTpsApi: Boolean = false
        override val supportsPlayerPing: Boolean = true
        override val supportsProtocolVersion: Boolean = true
        override val supportsPlayerAddress: Boolean = true
        override val supportsNetworkSwitches: Boolean = true
        // The brand channel is not surfaced by this API, and the world a player
        // is in and whether they are idle belong to the backend server.
        override val supportsClientBrand: Boolean = false
        override val supportsPlayerWorld: Boolean = false
        override val supportsActivityTracking: Boolean = false
    }

    override fun scheduler(): SchedulerAdapter = schedulerAdapter

    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> = emptyList()

    override fun players(): List<PlayerIntrospection> =
        proxy.players.map { BungeePlayerIntrospection(it) }

    override fun logger(): LoggerAdapter = loggerAdapter

    override val commandLabel: String get() = COMMAND_LABEL

    override fun registerCommands(registry: CommandRegistry) {
        // Registered by the plugin entry point, which owns the plugin instance
        // Bungee keys command registration on.
    }

    override fun dataFolder(): File = pluginDataFolder

    companion object {
        const val COMMAND_LABEL = "serverspulseproxy"
    }
}
