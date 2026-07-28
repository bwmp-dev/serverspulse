package com.serverspulse.agent.core

import com.serverspulse.agent.api.*
import com.serverspulse.agent.api.dto.PlayerEventPayload
import com.serverspulse.agent.core.collector.SnapshotAssembler
import com.serverspulse.agent.core.command.CommandDispatcher
import com.serverspulse.agent.core.command.ConsoleCommandRunner
import com.serverspulse.agent.core.command.ServersPulseCommandHandler
import com.serverspulse.agent.core.command.SparkHandler
import com.serverspulse.agent.core.config.AgentConfig
import com.serverspulse.agent.core.config.ConfigLoader
import com.serverspulse.agent.core.transport.TransportClient
import java.util.concurrent.atomic.AtomicLong
import com.serverspulse.agent.core.transport.TransportClient.Companion.RedeemResult

/**
 * The platform-agnostic runtime that drives the entire agent lifecycle:
 * 1. Load config
 * 2. Build transport + collector pipeline
 * 3. Start the repeating collection loop
 * 4. Handle commands from backend responses
 * 5. Clean shutdown
 *
 * Platform modules create an [AgentRuntime] with their [PlatformAdapter] and call [start]/[stop].
 * The [commandHandler] is exposed so platforms can wire it into their native command system.
 */
class AgentRuntime(
    private val platform: PlatformAdapter,
    private val consoleCommandRunner: ConsoleCommandRunner
) {
    private val logger: LoggerAdapter = platform.logger()

    private lateinit var config: AgentConfig
    private lateinit var transport: TransportClient
    private lateinit var backendCommandDispatcher: CommandDispatcher

    // Assembler only depends on `platform` (a constructor param), so it can be
    // initialized eagerly.  This avoids the lateinit gap when reload() starts
    // the collection loop after a failed start().
    private val assembler = SnapshotAssembler(platform)

    private var collectionTask: TaskHandle? = null
    private val ingestSequence = AtomicLong(0)
    private val lastAppliedCommandSequence = AtomicLong(0)

    @Volatile
    private var running = false

    /**
     * Shared command handler for `/serverspulse`.
     * Platforms should route their native command events to this handler.
     */
    val commandHandler: CommandHandler = ServersPulseCommandHandler(this)

    /**
     * Initialize and start the agent.
     * Call this from the platform's onEnable/initialization hook.
     */
    fun start() {
        logger.info("ServersPulse agent starting on ${platform.platformId} (MC ${platform.mcVersion})")

        // Load config
        config = ConfigLoader.load(platform.dataFolder(), logger)
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            errors.forEach { logger.error(it) }
            logger.error("ServersPulse agent disabled due to config errors.")
            return
        }

        if (config.debug) {
            logger.info("Debug logging enabled")
            logCapabilities()
        }

        // Build components
        transport = TransportClient(
            backendUrl = config.backendUrl,
            apiKey = config.apiKey,
            logger = logger
        )

        sendAgentInfoAsync()
        checkForUpdatesAsync()

        backendCommandDispatcher = CommandDispatcher(logger)
        registerBackendCommands()

        // Start collection loop
        startCollectionLoop()

        logger.info("ServersPulse agent started. Collecting every ${config.intervalSeconds}s.")
    }

    /**
     * Stop the agent and release resources.
     * Call this from the platform's onDisable/shutdown hook.
     */
    fun stop() {
        running = false
        collectionTask?.cancel()
        collectionTask = null

        if (::backendCommandDispatcher.isInitialized) {
            backendCommandDispatcher.shutdown()
        }
        if (::transport.isInitialized) {
            transport.shutdown()
        }

        logger.info("ServersPulse agent stopped.")
    }

    /**
     * Reload configuration from disk and restart the collection loop.
     * Called by the shared [ServersPulseCommandHandler] on `/serverspulse reload`.
     *
     * @return result indicating success or validation errors
     */
    fun reload(): ReloadResult {
        val newConfig = ConfigLoader.load(platform.dataFolder(), logger)
        val errors = newConfig.validate()
        if (errors.isNotEmpty()) {
            return ReloadResult(success = false, errors = errors)
        }

        // Stop current collection
        collectionTask?.cancel()
        collectionTask = null

        // Rebuild transport with potentially new API key / backend URL
        if (::backendCommandDispatcher.isInitialized) {
            backendCommandDispatcher.shutdown()
        }
        if (::transport.isInitialized) {
            transport.shutdown()
        }

        config = newConfig
        transport = TransportClient(
            backendUrl = config.backendUrl,
            apiKey = config.apiKey,
            logger = logger
        )

        sendAgentInfoAsync()
        checkForUpdatesAsync()

        // Rebuild backend command dispatcher with new transport
        backendCommandDispatcher = CommandDispatcher(logger)
        registerBackendCommands()

        // Restart collection
        startCollectionLoop()

        logger.info("Configuration reloaded. Collecting every ${config.intervalSeconds}s.")
        return ReloadResult(success = true, errors = emptyList())
    }

    /**
     * Registers this server with the ServersPulse backend using a one-time code.
     *
     * The HTTP exchange runs asynchronously. On success the API key is written
     * to config.yml and the agent is reloaded so monitoring starts immediately.
     * Progress and result messages are sent to [sender].
     *
     * @param code   the one-time registration code from the dashboard
     * @param sender the command sender to receive feedback messages
     */
    fun register(code: String, sender: CommandSender) {
        // Load config even if the agent isn't running (api-key may be blank)
        val currentConfig = ConfigLoader.load(platform.dataFolder(), logger)

        if (currentConfig.apiKey.isNotBlank()) {
            sender.sendMessage("[ServersPulse] Warning: An API key is already configured. This will overwrite it.")
        }

        sender.sendMessage("[ServersPulse] Contacting the ServersPulse backend...")

        platform.scheduler().runAsync(Runnable {
            try {
                val result: RedeemResult = TransportClient.redeemCode(
                    backendUrl = currentConfig.backendUrl,
                    code = code,
                    logger = logger
                )

                if (result.apiKey != null) {
                    ConfigLoader.saveApiKey(platform.dataFolder(), result.apiKey, logger)
                    sender.sendMessage("[ServersPulse] Registration successful! API key saved to config.yml.")

                    val reloadResult = reload()
                    if (reloadResult.success) {
                        sender.sendMessage("[ServersPulse] Monitoring started.")
                    } else {
                        reloadResult.errors.forEach {
                            sender.sendMessage("[ServersPulse] Error: $it")
                        }
                    }
                } else {
                    sender.sendMessage("[ServersPulse] Registration failed: ${result.error}")
                }
            } catch (e: Exception) {
                sender.sendMessage("[ServersPulse] Registration failed: ${e.message}")
                logger.error("Registration failed", e)
            }
        })
    }

    /**
     * Returns current agent status for the status command.
     */
    fun status(): AgentStatus {
        return AgentStatus(
            running = running,
            platform = platform.platformId,
            mcVersion = platform.mcVersion,
            intervalSeconds = if (::config.isInitialized) config.intervalSeconds else 0,
            backendUrl = if (::config.isInitialized) config.backendUrl else "not loaded"
        )
    }

    /**
     * Called by platform event listeners when a player joins the server.
     * Must be called from an async thread (not the main server thread).
     *
     * @param playerUuid Minecraft UUID string (with dashes)
     * @param username   Current in-game name
     * @param hostname   The server address the player connected to, or null
     *                   if unavailable on this platform.
     */
    fun onPlayerJoin(playerUuid: String, username: String, hostname: String?) {
        if (!running || !::transport.isInitialized) return
        val payload = PlayerEventPayload(
            event = "join",
            playerUuid = playerUuid,
            username = username,
            hostname = hostname
        )
        try {
            transport.sendPlayerEvent(payload)
        } catch (e: Exception) {
            logger.error("Failed to send player join event", e)
        }
    }

    /**
     * Called by platform event listeners when a player leaves the server.
     * Must be called from an async thread (not the main server thread).
     *
     * @param playerUuid Minecraft UUID string (with dashes)
     * @param username   Current in-game name
     */
    fun onPlayerLeave(playerUuid: String, username: String) {
        if (!running || !::transport.isInitialized) return
        val payload = PlayerEventPayload(
            event = "leave",
            playerUuid = playerUuid,
            username = username,
            hostname = null
        )
        try {
            transport.sendPlayerEvent(payload)
        } catch (e: Exception) {
            logger.error("Failed to send player leave event", e)
        }
    }

    private fun startCollectionLoop() {
        val intervalTicks = config.intervalSeconds.toLong() * 20L
        running = true
        collectionTask = platform.scheduler().scheduleRepeating(intervalTicks, Runnable {
            collectAndSend()
        })
    }

    /**
     * Collect a snapshot on the main thread, then send it async.
     */
    private fun collectAndSend() {
        if (!running) return

        try {
            // Assemble snapshot on main thread (safe world access)
            val snapshot = assembler.assemble()
            val requestSequence = ingestSequence.incrementAndGet()

            // Send on async thread to avoid blocking the server
            platform.scheduler().runAsync(Runnable {
                try {
                    val response = transport.sendSnapshot(snapshot)
                    if (response?.status == "accepted" && claimCommandSequence(requestSequence)) {
                        backendCommandDispatcher.dispatch(response.commands ?: emptyList())
                    }
                    if (config.debug) {
                        logger.debug("Snapshot sent: tps=${snapshot.tps}, " +
                            "players=${snapshot.onlinePlayers}, " +
                            "memory=${snapshot.memoryUsedMB}/${snapshot.memoryMaxMB}MB")
                    }
                } catch (e: Exception) {
                    logger.error("Failed to send snapshot", e)
                }
            })
        } catch (e: Exception) {
            logger.error("Failed to collect snapshot", e)
        }
    }

    private fun claimCommandSequence(sequence: Long): Boolean {
        while (true) {
            val applied = lastAppliedCommandSequence.get()
            if (sequence <= applied) return false
            if (lastAppliedCommandSequence.compareAndSet(applied, sequence)) return true
        }
    }

    private fun registerBackendCommands() {
        if (!config.allowBackendSparkProfiling) {
            logger.info("Backend-requested Spark profiling is disabled.")
            return
        }

        logger.warn("Backend-requested Spark profiling is enabled.")
        backendCommandDispatcher.register("spark_profile", SparkHandler(
            scheduler = platform.scheduler(),
            transport = transport,
            logger = logger,
            consoleCommandRunner = consoleCommandRunner
        ))
    }

    private fun logCapabilities() {
        val caps = platform.capabilities
        logger.debug("Capabilities: " +
            "mspt=${caps.supportsMspt}, " +
            "nativeTickTimes=${caps.supportsNativeTickTimes}, " +
            "asyncChunkStats=${caps.supportsAsyncChunkStats}, " +
            "tpsApi=${caps.supportsTpsApi}")
    }

    private fun sendAgentInfoAsync() {
        if (!::transport.isInitialized) return

        val agentVersion = AgentVersion.resolve()
        platform.scheduler().runAsync(Runnable {
            try {
                val ok = transport.sendAgentInfo(agentVersion)
                if (!ok && config.debug) {
                    logger.debug("Failed to update backend agent version metadata.")
                }
            } catch (e: Exception) {
                logger.debug("Agent metadata update failed: ${e.message}")
            }
        })
    }

    private fun checkForUpdatesAsync() {
        if (!::transport.isInitialized) return

        val currentVersion = AgentVersion.resolve()
        val platformId = platform.platformId
        val mcVersion = platform.mcVersion

        platform.scheduler().runAsync(Runnable {
            try {
                val result = transport.checkForUpdate(platformId, mcVersion, currentVersion)
                if (result.updateAvailable && !result.latestVersion.isNullOrBlank()) {
                    logger.warn(
                        "ServersPulse update available: $currentVersion -> ${result.latestVersion}. " +
                            "Download from https://serverspulse.com/downloads"
                    )
                } else if (result.error != null && config.debug) {
                    logger.debug("Update check skipped: ${result.error}")
                }
            } catch (e: Exception) {
                if (config.debug) {
                    logger.debug("Update check failed: ${e.message}")
                }
            }
        })
    }

    /** Result of a [reload] operation. */
    data class ReloadResult(val success: Boolean, val errors: List<String>)

    /** Snapshot of agent state for the status command. */
    data class AgentStatus(
        val running: Boolean,
        val platform: String,
        val mcVersion: String,
        val intervalSeconds: Int,
        val backendUrl: String
    )
}
