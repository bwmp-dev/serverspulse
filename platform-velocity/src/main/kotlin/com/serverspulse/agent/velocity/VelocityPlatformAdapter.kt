package com.serverspulse.agent.velocity

import com.serverspulse.agent.api.CommandRegistry
import com.serverspulse.agent.api.LoggerAdapter
import com.serverspulse.agent.api.PlatformAdapter
import com.serverspulse.agent.api.PlayerIntrospection
import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.api.VersionCapabilities
import com.serverspulse.agent.api.WorldIntrospection
import com.velocitypowered.api.proxy.ProxyServer
import java.io.File
import java.nio.file.Path

/**
 * Top-level [PlatformAdapter] for a Velocity proxy.
 *
 * A proxy is a normal platform to the core runtime, minus the two things it
 * genuinely does not have: a tick loop and worlds. Both are reported as absent
 * rather than filled in.
 */
class VelocityPlatformAdapter(
    private val proxy: ProxyServer,
    private val schedulerAdapter: VelocitySchedulerAdapter,
    private val loggerAdapter: LoggerAdapter,
    private val dataDirectory: Path
) : PlatformAdapter {

    private val serverIntrospection = VelocityServerIntrospection(proxy)

    override val platformId: String = "velocity"

    override val mcVersion: String
        get() = proxy.version.version

    override val capabilities: VersionCapabilities = object : VersionCapabilities {
        override val supportsMspt: Boolean = false
        override val supportsNativeTickTimes: Boolean = false
        override val supportsAsyncChunkStats: Boolean = false
        override val supportsTpsApi: Boolean = false
        override val supportsPlayerPing: Boolean = true
        override val supportsClientBrand: Boolean = true
        override val supportsProtocolVersion: Boolean = true
        override val supportsPlayerAddress: Boolean = true
        override val supportsNetworkSwitches: Boolean = true
        // The world a player is in and whether they are idle are properties of
        // the backend server they are connected to, not of the proxy.
        override val supportsPlayerWorld: Boolean = false
        override val supportsActivityTracking: Boolean = false
    }

    override fun scheduler(): SchedulerAdapter = schedulerAdapter

    override fun server(): ServerIntrospection = serverIntrospection

    override fun worlds(): List<WorldIntrospection> = emptyList()

    override fun players(): List<PlayerIntrospection> =
        proxy.allPlayers.map { VelocityPlayerIntrospection(it) }

    override fun logger(): LoggerAdapter = loggerAdapter

    override val commandLabel: String get() = COMMAND_LABEL

    override fun registerCommands(registry: CommandRegistry) {
        // Registered by the plugin entry point, which owns the CommandManager
        // handle and the plugin instance the registration is keyed on.
    }

    override fun dataFolder(): File = dataDirectory.toFile()

    companion object {
        const val COMMAND_LABEL = "serverspulseproxy"
    }
}
