package com.serverspulse.agent.core.config

/**
 * Plugin configuration model.
 * Loaded from config.yml in the plugin's data folder.
 */
data class AgentConfig(
    /** The API key used to authenticate with the backend. */
    val apiKey: String = "",

    /** The backend ingestion URL. */
    val backendUrl: String = DEFAULT_BACKEND_URL,

    /** Snapshot collection interval in seconds. */
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,

    /** Whether debug logging is enabled. */
    val debug: Boolean = false,

    /** Whether the backend may request and receive Spark profiling results. */
    val allowBackendSparkProfiling: Boolean = false,

    /** Whether per-player metrics (AFK, ping, world time) are collected at all. */
    val collectPlayerMetrics: Boolean = true,

    /** Idle time after which a player counts as AFK. */
    val afkThresholdSeconds: Int = DEFAULT_AFK_THRESHOLD_SECONDS,

    /** How often accumulated session counters are sent to the backend. */
    val sessionFlushSeconds: Int = DEFAULT_SESSION_FLUSH_SECONDS,

    /**
     * Whether joining players' addresses are resolved to a country code.
     *
     * Opt-in: it is the only collector that reads a personal identifier, even
     * though only the derived country ever leaves the machine.
     */
    val geolocationEnabled: Boolean = false,

    /** How often mirrored plugin state (rank, balance, punishments) is sent. */
    val extensionFlushSeconds: Int = DEFAULT_EXTENSION_FLUSH_SECONDS
) {
    companion object {
        const val DEFAULT_BACKEND_URL = "https://api.serverspulse.com"
        const val DEFAULT_INTERVAL_SECONDS = 30
        const val MIN_INTERVAL_SECONDS = 5
        const val CONFIG_FILE_NAME = "config.yml"
        const val DEFAULT_AFK_THRESHOLD_SECONDS = 300
        const val DEFAULT_SESSION_FLUSH_SECONDS = 300
        const val MIN_AFK_THRESHOLD_SECONDS = 30
        const val MIN_SESSION_FLUSH_SECONDS = 60
        const val DEFAULT_EXTENSION_FLUSH_SECONDS = 900
        const val MIN_EXTENSION_FLUSH_SECONDS = 300
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (apiKey.isBlank()) {
            errors.add("api-key must be set in config.yml")
        }
        if (backendUrl.isBlank()) {
            errors.add("backend-url must not be empty")
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            errors.add("interval-seconds must be at least $MIN_INTERVAL_SECONDS")
        }
        if (collectPlayerMetrics && afkThresholdSeconds < MIN_AFK_THRESHOLD_SECONDS) {
            errors.add("afk-threshold-seconds must be at least $MIN_AFK_THRESHOLD_SECONDS")
        }
        if (collectPlayerMetrics && sessionFlushSeconds < MIN_SESSION_FLUSH_SECONDS) {
            errors.add("session-flush-seconds must be at least $MIN_SESSION_FLUSH_SECONDS")
        }
        if (extensionFlushSeconds < MIN_EXTENSION_FLUSH_SECONDS) {
            errors.add("extension-flush-seconds must be at least $MIN_EXTENSION_FLUSH_SECONDS")
        }
        return errors
    }
}
