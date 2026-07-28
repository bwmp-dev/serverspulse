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
    val allowBackendSparkProfiling: Boolean = false
) {
    companion object {
        const val DEFAULT_BACKEND_URL = "https://api.serverspulse.com"
        const val DEFAULT_INTERVAL_SECONDS = 30
        const val MIN_INTERVAL_SECONDS = 5
        const val CONFIG_FILE_NAME = "config.yml"
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
        return errors
    }
}
