package com.serverspulse.agent.core.config

import com.serverspulse.agent.api.LoggerAdapter
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileWriter

/**
 * Loads and saves [AgentConfig] from/to a YAML file in the plugin's data folder.
 */
object ConfigLoader {

    private val DEFAULT_CONFIG_CONTENT = """
        # ServersPulse Agent Configuration
        # https://serverspulse.com
        
        # Your server's API key (get this from the ServersPulse dashboard)
        api-key: ""
        
        # Backend URL (do not change unless self-hosting)
        backend-url: "https://api.serverspulse.com"
        
        # Snapshot collection interval in seconds
        # Free plan minimum: 30s, Paid plans: 5-10s
        interval-seconds: 30
        
        # Enable debug logging
        debug: false

        # Allow the backend to start Spark profiles and receive their result URLs.
        # This executes Spark console commands. Keep disabled unless you accept
        # that trust boundary and have installed the Spark plugin/mod.
        allow-backend-spark-profiling: false

        # Collect per-player AFK time, latency and world time. Only what this
        # server version can actually measure is reported; the dashboard shows
        # the rest as "not reported" rather than as zero.
        collect-player-metrics: true

        # Idle time before a player counts as AFK, in seconds.
        afk-threshold-seconds: 300

        # How often accumulated session counters are sent, in seconds.
        # Counters are cumulative, so a missed flush costs at most this much.
        session-flush-seconds: 300

        # Resolve each joining player's address to a country code, on this
        # machine, using a database downloaded once a month. Only the two-letter
        # country code is sent; the address itself never leaves this server and
        # is never written to a log. Off by default.
        geolocation-enabled: false

        # How often mirrored plugin state (LuckPerms rank, Vault balance,
        # punishments) is sent, in seconds. Nothing is sent when none of those
        # plugins is installed.
        extension-flush-seconds: 900
    """.trimIndent()

    /**
     * Writes the given API key into config.yml, preserving all other
     * settings and comments.  If the file does not exist it is created
     * from the default template with the key already filled in.
     */
    fun saveApiKey(dataFolder: File, apiKey: String, logger: LoggerAdapter) {
        val configFile = File(dataFolder, AgentConfig.CONFIG_FILE_NAME)

        if (!configFile.exists()) {
            dataFolder.mkdirs()
            FileWriter(configFile).use {
                it.write(DEFAULT_CONFIG_CONTENT.replace("api-key: \"\"", "api-key: \"$apiKey\""))
            }
            logger.info("Created config.yml with the registered API key.")
            return
        }

        val lines = configFile.readLines().toMutableList()
        var found = false
        for (i in lines.indices) {
            if (lines[i].trimStart().startsWith("api-key:")) {
                lines[i] = "api-key: \"$apiKey\""
                found = true
                break
            }
        }
        if (!found) {
            lines.add("api-key: \"$apiKey\"")
        }

        configFile.writeText(lines.joinToString("\n"))
        logger.info("API key saved to config.yml.")
    }

    fun load(dataFolder: File, logger: LoggerAdapter): AgentConfig {
        val configFile = File(dataFolder, AgentConfig.CONFIG_FILE_NAME)

        if (!configFile.exists()) {
            dataFolder.mkdirs()
            FileWriter(configFile).use { it.write(DEFAULT_CONFIG_CONTENT) }
            logger.info("Created default config.yml - please set your API key.")
            return AgentConfig()
        }

        return try {
            val yaml = Yaml()
            val data: Map<String, Any?> = configFile.inputStream().use { yaml.load(it) }
                ?: return AgentConfig()

            AgentConfig(
                apiKey = (data["api-key"] as? String) ?: "",
                backendUrl = (data["backend-url"] as? String) ?: AgentConfig.DEFAULT_BACKEND_URL,
                intervalSeconds = (data["interval-seconds"] as? Number)?.toInt()
                    ?: AgentConfig.DEFAULT_INTERVAL_SECONDS,
                debug = (data["debug"] as? Boolean) ?: false,
                allowBackendSparkProfiling =
                    (data["allow-backend-spark-profiling"] as? Boolean) ?: false,
                collectPlayerMetrics = (data["collect-player-metrics"] as? Boolean) ?: true,
                afkThresholdSeconds = (data["afk-threshold-seconds"] as? Number)?.toInt()
                    ?: AgentConfig.DEFAULT_AFK_THRESHOLD_SECONDS,
                sessionFlushSeconds = (data["session-flush-seconds"] as? Number)?.toInt()
                    ?: AgentConfig.DEFAULT_SESSION_FLUSH_SECONDS,
                geolocationEnabled = (data["geolocation-enabled"] as? Boolean) ?: false,
                extensionFlushSeconds = (data["extension-flush-seconds"] as? Number)?.toInt()
                    ?: AgentConfig.DEFAULT_EXTENSION_FLUSH_SECONDS
            )
        } catch (e: Exception) {
            logger.error("Failed to load config.yml, using defaults", e)
            AgentConfig()
        }
    }
}
