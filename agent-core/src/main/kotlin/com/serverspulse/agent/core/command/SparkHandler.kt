package com.serverspulse.agent.core.command

import com.serverspulse.agent.api.LoggerAdapter
import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.TaskHandle
import com.serverspulse.agent.api.dto.CommandPayload
import com.serverspulse.agent.api.dto.SparkResultPayload
import com.serverspulse.agent.core.transport.TransportClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles "spark_profile" commands from the backend.
 *
 * When the backend detects a performance incident and the alert rule has sparkProfile enabled,
 * it sends a command asking the plugin to run a Spark profiler session.
 *
 * This handler dispatches the Spark console command and, after the profile duration,
 * attempts to read the Spark result URL and report it back.
 *
 * NOTE: This is a best-effort integration. It requires the Spark plugin to be installed.
 * If Spark is not present, the command is logged and skipped.
 */
class SparkHandler(
    private val scheduler: SchedulerAdapter,
    private val transport: TransportClient,
    private val logger: LoggerAdapter,
    private val consoleCommandRunner: ConsoleCommandRunner
) : CommandExecutor, CommandBatchAware, CommandShutdownAware {

    companion object {
        private const val DEFAULT_DURATION_SECONDS = 600
        private const val MIN_DURATION_SECONDS = 60
        private const val MAX_DURATION_SECONDS = 600
        private const val MONITOR_INTERVAL_TICKS = 20L * 5L
        private const val RESULT_RETRY_INTERVAL_TICKS = 20L * 5L
        private const val RESULT_RETRY_TIMEOUT_MILLIS = 5L * 60L * 1000L
        private const val LOG_PATH = "logs/latest.log"
        private val SPARK_URL_REGEX = Regex("https?://spark\\.lucko\\.me/\\S+")
    }

    private val stateLock = Any()
    private val suppressedIncidents = mutableSetOf<String>()
    private val finishingSessions = mutableMapOf<String, SparkSession>()
    private var activeSession: SparkSession? = null

    override fun onCommandBatch(commands: List<CommandPayload>) {
        val pendingSparkIncidents = commands
            .asSequence()
            .filter { it.type == "spark_profile" }
            .mapNotNull { it.incidentId }
            .toSet()

        val activeIncidentId = synchronized(stateLock) {
            suppressedIncidents.retainAll(pendingSparkIncidents)
            activeSession?.incidentId
        } ?: return

        val stillRequested = activeIncidentId in pendingSparkIncidents
        if (!stillRequested) {
            endActiveSession(
                incidentId = activeIncidentId,
                reason = "Incident $activeIncidentId no longer requests Spark profiling. Stopping active profiler.",
                stopProfiler = true,
                markCompleted = false
            )
        }
    }

    override fun execute(command: CommandPayload) {
        val incidentId = command.incidentId

        if (incidentId == null) {
            logger.warn("Spark profile command missing incidentId, ignoring.")
            return
        }

        val now = System.currentTimeMillis()
        val requestedDuration = command.durationSeconds ?: DEFAULT_DURATION_SECONDS
        val duration = requestedDuration.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)

        synchronized(stateLock) {
            if (incidentId in suppressedIncidents) {
                logger.debug("Spark profile already completed for incident $incidentId")
                return
            }
            if (incidentId in finishingSessions) {
                logger.debug("Spark profile is waiting for result persistence for incident $incidentId")
                return
            }

            val running = activeSession
            if (running != null) {
                if (running.incidentId == incidentId) {
                    logger.debug("Spark profile already active for incident $incidentId")
                } else {
                    logger.debug("Spark profile already active for incident ${running.incidentId}; skipping $incidentId")
                }
                return
            }

            activeSession = SparkSession(
                incidentId = incidentId,
                durationSeconds = duration,
                startedAtMillis = now,
                logTail = SparkLogTail(File(LOG_PATH))
            )
        }

        logger.info("Backend requested Spark profile for incident $incidentId (${duration}s)")

        try {
            consoleCommandRunner.run("spark profiler start --timeout $duration")
        } catch (e: Exception) {
            logger.warn("Failed to start Spark profiler (is Spark installed?): ${e.message}")
            synchronized(stateLock) {
                if (activeSession?.incidentId == incidentId) {
                    activeSession = null
                }
            }
            return
        }

        val monitorTask = scheduler.scheduleRepeating(MONITOR_INTERVAL_TICKS, Runnable {
            scheduler.runAsync(Runnable {
                monitorSparkSession(incidentId)
            })
        })

        synchronized(stateLock) {
            val session = activeSession
            if (session?.incidentId == incidentId) {
                session.monitorTask = monitorTask
            } else {
                monitorTask.cancel()
            }
        }
    }

    override fun onCommandShutdown() {
        val active = synchronized(stateLock) {
            val current = activeSession
            activeSession = null
            current?.monitorTask?.cancel()
            current?.monitorTask = null
            finishingSessions.values.forEach {
                it.resultTask?.cancel()
                it.resultTask = null
            }
            finishingSessions.clear()
            suppressedIncidents.clear()
            current
        }
        if (active != null) {
            try {
                consoleCommandRunner.run("spark profiler stop")
            } catch (e: Exception) {
                logger.debug("Failed to stop Spark profiler during shutdown: ${e.message}")
            }
        }
    }

    private fun monitorSparkSession(incidentId: String) {
        val session = synchronized(stateLock) {
            val current = activeSession
            if (current == null || current.incidentId != incidentId) {
                null
            } else {
                current
            }
        } ?: return

        val now = System.currentTimeMillis()
        val maxEndMillis = session.startedAtMillis + (session.durationSeconds * 1000L) + 30_000L

        if (now >= maxEndMillis) {
            endActiveSession(
                incidentId = incidentId,
                reason = "Spark profile for incident $incidentId reached max duration. Stopping profiler.",
                stopProfiler = true,
                markCompleted = false
            )
            return
        }

        val sparkUrl = try {
            session.resultUrl ?: session.logTail.nextSparkUrl()?.also { session.resultUrl = it }
        } catch (e: Exception) {
            logger.debug("Spark result retrieval failed: ${e.message}")
            null
        }

        if (sparkUrl.isNullOrBlank()) {
            return
        }

        if (!session.uploadInProgress.compareAndSet(false, true)) {
            return
        }

        try {
            val sent = uploadSparkResult(incidentId, sparkUrl)
            if (sent) {
                endActiveSession(
                    incidentId = incidentId,
                    reason = "Uploaded Spark profile for incident $incidentId: $sparkUrl",
                    stopProfiler = false,
                    markCompleted = true
                )
            } else {
                logger.warn("Failed to upload Spark result for incident $incidentId, will retry.")
                session.uploadInProgress.set(false)
            }
        } catch (e: Exception) {
            logger.warn("Failed to upload Spark result for incident $incidentId: ${e.message}")
            session.uploadInProgress.set(false)
        }
    }

    private fun uploadSparkResult(incidentId: String, sparkUrl: String): Boolean {
        return transport.sendSparkResult(SparkResultPayload(incidentId = incidentId, sparkUrl = sparkUrl))
    }

    private fun endActiveSession(
        incidentId: String,
        reason: String,
        stopProfiler: Boolean,
        markCompleted: Boolean
    ) {
        val endedSession = synchronized(stateLock) {
            val current = activeSession
            if (current == null || current.incidentId != incidentId) {
                null
            } else {
                activeSession = null
                current.monitorTask?.cancel()
                current.monitorTask = null
                if (markCompleted) {
                    suppressedIncidents.add(incidentId)
                }
                current
            }
        }
        if (endedSession == null) return

        if (stopProfiler) {
            try {
                consoleCommandRunner.run("spark profiler stop")
            } catch (e: Exception) {
                logger.debug("Failed to stop Spark profiler: ${e.message}")
            }

            beginResultCapture(endedSession)
        }

        logger.info(reason)
    }

    private fun beginResultCapture(session: SparkSession) {
        session.resultCaptureDeadlineMillis = System.currentTimeMillis() + RESULT_RETRY_TIMEOUT_MILLIS
        synchronized(stateLock) {
            finishingSessions[session.incidentId] = session
        }

        val task = scheduler.scheduleRepeating(RESULT_RETRY_INTERVAL_TICKS, Runnable {
            scheduler.runAsync(Runnable { retryResultCapture(session.incidentId) })
        })
        synchronized(stateLock) {
            if (finishingSessions[session.incidentId] === session) {
                session.resultTask = task
            } else {
                task.cancel()
            }
        }
        scheduler.runAsync(Runnable { retryResultCapture(session.incidentId) })
    }

    private fun retryResultCapture(incidentId: String) {
        val session = synchronized(stateLock) { finishingSessions[incidentId] } ?: return
        if (!session.uploadInProgress.compareAndSet(false, true)) return

        try {
            val sparkUrl = session.resultUrl ?: try {
                session.logTail.nextSparkUrl()?.also { session.resultUrl = it }
            } catch (e: Exception) {
                logger.debug("Spark result retrieval failed after stop: ${e.message}")
                null
            }

            if (!sparkUrl.isNullOrBlank()) {
                val sent = try {
                    uploadSparkResult(incidentId, sparkUrl)
                } catch (e: Exception) {
                    logger.warn("Failed to upload Spark result for incident $incidentId after stopping profiler: ${e.message}")
                    false
                }
                if (sent) {
                    finishResultCapture(session, persisted = true)
                    logger.info("Uploaded Spark profile for incident $incidentId: $sparkUrl")
                    return
                }
                logger.warn("Failed to upload Spark result for incident $incidentId after stopping profiler; will retry.")
            }

            if (System.currentTimeMillis() >= session.resultCaptureDeadlineMillis) {
                finishResultCapture(session, persisted = false)
                logger.warn("Timed out waiting to persist Spark result for incident $incidentId; profiling may be requested again.")
            }
        } finally {
            session.uploadInProgress.set(false)
        }
    }

    private fun finishResultCapture(session: SparkSession, persisted: Boolean) {
        synchronized(stateLock) {
            if (finishingSessions[session.incidentId] !== session) return
            finishingSessions.remove(session.incidentId)
            session.resultTask?.cancel()
            session.resultTask = null
            if (persisted) {
                suppressedIncidents.add(session.incidentId)
            }
        }
    }

    private class SparkSession(
        val incidentId: String,
        val durationSeconds: Int,
        val startedAtMillis: Long,
        val logTail: SparkLogTail,
        val uploadInProgress: AtomicBoolean = AtomicBoolean(false),
        @Volatile var monitorTask: TaskHandle? = null,
        @Volatile var resultTask: TaskHandle? = null,
        @Volatile var resultUrl: String? = null,
        @Volatile var resultCaptureDeadlineMillis: Long = 0L
    )

    private class SparkLogTail(private val logFile: File) {
        private var cursor: Long = if (logFile.exists()) logFile.length() else 0L

        @Synchronized
        fun nextSparkUrl(): String? {
            if (!logFile.exists()) return null

            RandomAccessFile(logFile, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength < cursor) {
                    cursor = 0L
                }

                raf.seek(cursor)
                var latestUrl: String? = null
                var line = raf.readLine()
                while (line != null) {
                    val match = SPARK_URL_REGEX.find(line)
                    if (match != null) {
                        latestUrl = sanitizeUrl(match.value)
                    }
                    line = raf.readLine()
                }

                cursor = raf.filePointer
                return latestUrl
            }
        }

        private fun sanitizeUrl(raw: String): String {
            return raw.trimEnd('.', ',', ')', ']', '"', '\'')
        }
    }
}

/**
 * Abstraction for running console commands on the server.
 * Platform modules provide the implementation.
 */
interface ConsoleCommandRunner {
    fun run(command: String)
}
