package com.serverspulse.agent.core

import com.serverspulse.agent.api.PlatformAdapter
import com.serverspulse.agent.core.collector.SystemMetrics
import com.serverspulse.agent.core.config.AgentConfig

/**
 * Derives the capability list the agent announces to the backend.
 *
 * Without it the dashboard cannot tell a server with no AFK time from a server
 * whose agent build cannot measure AFK time, and would draw an empty chart for
 * both. The names are a shared contract with the backend's
 * models/agent_capability.go.
 */
object AgentCapabilityReport {

    private const val PLAYER_PING = "player_ping"
    private const val CLIENT_BRAND = "client_brand"
    private const val PROTOCOL_VERSION = "protocol_version"
    private const val AFK_TRACKING = "afk_tracking"
    private const val WORLD_TIME = "world_time"
    private const val CPU_METRICS = "cpu_metrics"
    private const val DISK_METRICS = "disk_metrics"
    private const val GEOLOCATION = "geolocation"
    private const val NETWORK_SWITCHES = "network_switches"

    /**
     * A capability is reported only when the platform can measure it *and* the
     * owner has left the collection enabled, because a disabled collector and a
     * missing one look identical from the dashboard's side.
     *
     * @param geolocationReady whether the country database finished loading;
     *                         the feature being switched on is not the same as
     *                         it having a database to answer from.
     */
    fun resolve(
        platform: PlatformAdapter,
        config: AgentConfig,
        geolocationReady: Boolean = false
    ): List<String> {
        val capabilities = mutableListOf<String>()
        val caps = platform.capabilities

        if (config.collectPlayerMetrics) {
            if (caps.supportsPlayerPing) capabilities.add(PLAYER_PING)
            if (caps.supportsClientBrand) capabilities.add(CLIENT_BRAND)
            if (caps.supportsProtocolVersion) capabilities.add(PROTOCOL_VERSION)
            if (caps.supportsActivityTracking) capabilities.add(AFK_TRACKING)
            if (caps.supportsPlayerWorld) capabilities.add(WORLD_TIME)
        }

        if (config.geolocationEnabled && caps.supportsPlayerAddress && geolocationReady) {
            capabilities.add(GEOLOCATION)
        }

        if (caps.supportsNetworkSwitches) capabilities.add(NETWORK_SWITCHES)

        if (SystemMetrics.supportsCpuMetrics()) capabilities.add(CPU_METRICS)
        if (SystemMetrics.diskTotalMB(platform.dataFolder()) != null) capabilities.add(DISK_METRICS)

        platform.extensions()?.let { capabilities.addAll(it.capabilities()) }

        return capabilities
    }
}
