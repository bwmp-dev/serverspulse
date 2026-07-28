package com.serverspulse.agent.core.transport

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.serverspulse.agent.api.LoggerAdapter
import com.serverspulse.agent.api.dto.IngestResponse
import com.serverspulse.agent.api.dto.PlayerEventPayload
import com.serverspulse.agent.api.dto.ServerSnapshot
import com.serverspulse.agent.api.dto.SparkResultPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for communicating with the ServersPulse backend.
 * Handles JSON serialization, API key auth, and retry with exponential backoff.
 */
class TransportClient(
    private val backendUrl: String,
    private val apiKey: String,
    private val logger: LoggerAdapter,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val INGEST_PATH = "/api/v1/ingest"
        private const val AGENT_INFO_PATH = "/api/v1/ingest/agent-info"
        private const val SPARK_RESULT_PATH = "/api/v1/ingest/spark-result"
        private const val PLAYER_EVENT_PATH = "/api/v1/ingest/player-event"
        private const val LATEST_RELEASE_PATH = "/api/v1/releases/latest?channel=stable"
        private const val REGISTER_PATH = "/api/v1/auth/claim"

        private val SEMVER =
            Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""")

        /**
         * Result of a one-time registration code redemption.
         * Exactly one of [apiKey] or [error] will be non-null.
         */
        data class RedeemResult(val apiKey: String?, val error: String?)

        data class UpdateCheckResult(val updateAvailable: Boolean, val latestVersion: String?, val error: String?)

        private data class ReleaseResponse(val version: String?, val artifacts: List<ReleaseArtifact>?)

        private data class ReleaseArtifact(val platform: String?, val mcVersion: String?)

        /**
         * Exchanges a one-time registration code for a permanent API key.
         * This is a standalone call that does not require an existing API key.
         *
         * @param backendUrl the base backend URL (e.g. "https://api.serverspulse.com")
         * @param code       the one-time code generated from the dashboard
         * @param logger     adapter for logging
         * @return [RedeemResult] with either the API key or an error message
         */
        fun redeemCode(backendUrl: String, code: String, logger: LoggerAdapter): RedeemResult {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            val url = backendUrl.trimEnd('/') + REGISTER_PATH
            val gson = Gson()
            val json = gson.toJson(mapOf("code" to code))
            val body = json.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ServersPulse-Agent/1.0")
                .post(body)
                .build()

            return try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (response.isSuccessful) {
                        @Suppress("UNCHECKED_CAST")
                        val data = gson.fromJson(responseBody, Map::class.java) as? Map<String, Any?>
                        val apiKey = data?.get("apiKey") as? String
                        if (apiKey != null) {
                            RedeemResult(apiKey = apiKey, error = null)
                        } else {
                            RedeemResult(apiKey = null, error = "No API key in response.")
                        }
                    } else {
                        val errorMsg = when (response.code) {
                            400 -> "Invalid or expired registration code."
                            404 -> "Registration code not found."
                            409 -> "This code has already been used."
                            429 -> "Too many attempts. Please try again later."
                            else -> "Server error (${response.code}). Please try again."
                        }
                        RedeemResult(apiKey = null, error = errorMsg)
                    }
                }
            } catch (e: IOException) {
                logger.debug("Registration request failed: ${e.message}")
                RedeemResult(
                    apiKey = null,
                    error = "Could not connect to the ServersPulse backend. Check your connection and backend-url."
                )
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a snapshot to the ingest endpoint with retry.
     * This is called from an async thread - never from the main server thread.
     *
     * @return the parsed [IngestResponse], or null if all retries failed
     */
    fun sendSnapshot(snapshot: ServerSnapshot): IngestResponse? {
        val json = gson.toJson(snapshot)
        val url = backendUrl.trimEnd('/') + INGEST_PATH
        return postWithRetry(url, json)
    }

    /**
     * Sends plugin/mod metadata to the backend.
     * Intended to be called once on startup/reload.
     */
    fun sendAgentInfo(agentVersion: String): Boolean {
        val url = backendUrl.trimEnd('/') + AGENT_INFO_PATH
        val json = gson.toJson(mapOf("agentVersion" to agentVersion))
        val body = json.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .header("User-Agent", "ServersPulse-Agent/1.0")
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string().ifBlank { "no body" }
                    logger.warn("Agent info update rejected (${response.code}): $errorBody")
                    false
                } else {
                    true
                }
            }
        } catch (e: IOException) {
            logger.debug("Agent info update failed: ${e.message}")
            false
        }
    }

    /**
     * Checks whether a newer compatible stable release exists for this platform.
     */
    fun checkForUpdate(platformId: String, mcVersion: String, currentVersion: String): UpdateCheckResult {
        val url = backendUrl.trimEnd('/') + LATEST_RELEASE_PATH
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ServersPulse-Agent/1.0")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return UpdateCheckResult(false, null, null)
                }
                if (!response.isSuccessful) {
                    return UpdateCheckResult(
                        updateAvailable = false,
                        latestVersion = null,
                        error = "release check failed with HTTP ${response.code}"
                    )
                }

                val responseBody = response.body.string()
                val release = gson.fromJson(responseBody, ReleaseResponse::class.java)
                val latestVersion = release.version?.trim().orEmpty()
                if (latestVersion.isBlank()) {
                    return UpdateCheckResult(false, null, "release payload missing version")
                }

                val targetPlatform = mapPlatformToReleaseKey(platformId)
                val hasCompatibleArtifact = release.artifacts.orEmpty().any { artifact ->
                    val artifactPlatform = artifact.platform?.trim()?.lowercase().orEmpty()
                    if (artifactPlatform != targetPlatform) {
                        return@any false
                    }
                    val artifactMcVersion = artifact.mcVersion?.trim().orEmpty()
                    artifactMcVersion.isBlank() || artifactMcVersion.equals(mcVersion.trim(), ignoreCase = true)
                }

                if (!hasCompatibleArtifact) {
                    return UpdateCheckResult(false, latestVersion, null)
                }

                val isNewer = isVersionNewer(currentVersion, latestVersion)
                UpdateCheckResult(isNewer, latestVersion, null)
            }
        } catch (e: Exception) {
            UpdateCheckResult(false, null, "release check failed: ${e.message}")
        }
    }

    private fun mapPlatformToReleaseKey(platformId: String): String {
        val raw = platformId.trim().lowercase()
        return when {
            raw.contains("neoforge") -> "neoforge"
            raw.contains("forge") -> "forge"
            raw.contains("fabric") -> "fabric"
            else -> "bukkit"
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        val currentSemver = parseSemver(current)
        val latestSemver = parseSemver(latest)

        if (currentSemver != null && latestSemver != null) {
            return compareSemver(currentSemver, latestSemver) < 0
        }

        val normalizedCurrent = current.trim().lowercase().removePrefix("v")
        val normalizedLatest = latest.trim().lowercase().removePrefix("v")
        if (normalizedCurrent.isBlank() || normalizedCurrent == "unknown") {
            return normalizedLatest.isNotBlank() && normalizedLatest != "unknown"
        }
        return normalizedLatest != normalizedCurrent
    }

    private data class Semver(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String?
    )

    private fun parseSemver(raw: String): Semver? {
        val match = SEMVER.matchEntire(raw.trim()) ?: return null

        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val preRelease = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
        return Semver(major, minor, patch, preRelease)
    }

    private fun compareSemver(a: Semver, b: Semver): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        if (a.patch != b.patch) return a.patch.compareTo(b.patch)

        if (a.preRelease == null && b.preRelease == null) return 0
        if (a.preRelease == null) return 1
        if (b.preRelease == null) return -1
        return a.preRelease.compareTo(b.preRelease)
    }

    /**
     * Sends a Spark profile result to the backend.
     *
     * @return true if the request succeeded
     */
    fun sendSparkResult(payload: SparkResultPayload): Boolean {
        val json = gson.toJson(payload)
        val url = backendUrl.trimEnd('/') + SPARK_RESULT_PATH

        var attempt = 0
        while (true) {
            val body = json.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .header("X-API-Key", apiKey)
                .header("User-Agent", "ServersPulse-Agent/1.0")
                .post(body)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return true
                    }

                    val errorBody = response.body.string().ifBlank { "no body" }
                    logger.warn("Spark result upload rejected (${response.code}): $errorBody")

                    if (response.code in 400..499 && response.code != 429) {
                        if (response.code == 401 || response.code == 403) {
                            logger.error("Authentication failed (${response.code}). Check your API key in config.yml.")
                        }
                        return false
                    }
                }
            } catch (e: IOException) {
                logger.debug("Spark upload attempt $attempt failed: ${e.message}")
            }

            val delay = retryPolicy.delayForAttempt(attempt)
            if (delay == null) {
                logger.warn("All spark result retries exhausted. Dropping result for incident ${payload.incidentId}.")
                return false
            }

            attempt++
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * Sends a player join or leave event to the backend.
     * Fire-and-forget: failures are logged but not retried to avoid blocking
     * the server thread that dispatched the event.
     *
     * @return true if the backend accepted the event
     */
    fun sendPlayerEvent(payload: PlayerEventPayload): Boolean {
        val json = gson.toJson(payload)
        val url = backendUrl.trimEnd('/') + PLAYER_EVENT_PATH
        val body = json.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .header("User-Agent", "ServersPulse-Agent/1.0")
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string().ifBlank { "no body" }
                    logger.warn("Player event rejected (${response.code}): $errorBody")
                    false
                } else {
                    true
                }
            }
        } catch (e: IOException) {
            logger.debug("Player event send failed: ${e.message}")
            false
        }
    }

    private fun postWithRetry(url: String, json: String): IngestResponse? {
        var attempt = 0
        while (true) {
            try {
                val result = doPost(url, json)
                if (result != null) return result
            } catch (e: IOException) {
                logger.debug("Ingest attempt $attempt failed: ${e.message}")
            }

            val delay = retryPolicy.delayForAttempt(attempt)
            if (delay == null) {
                logger.warn("All ingest retries exhausted. Dropping snapshot.")
                return null
            }

            attempt++
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    private fun doPost(url: String, json: String): IngestResponse? {
        val body = json.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey)
            .header("User-Agent", "ServersPulse-Agent/1.0")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string().ifBlank { "no body" }
                logger.debug("Ingest returned ${response.code}: $errorBody")
                // Don't retry on 4xx client errors (bad key, validation failures)
                if (response.code in 400..499) {
                    if (response.code == 401 || response.code == 403) {
                        logger.error("Authentication failed (${response.code}). Check your API key in config.yml.")
                    } else {
                        logger.warn("Ingest rejected (${response.code}): $errorBody")
                    }
                    return IngestResponse(status = "error", commands = null)
                }
                throw IOException("Server returned ${response.code}")
            }

            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                return IngestResponse(status = "accepted", commands = null)
            }
            return try {
                gson.fromJson(responseBody, IngestResponse::class.java)
            } catch (e: Exception) {
                logger.debug("Failed to parse ingest response: ${e.message}")
                IngestResponse(status = "accepted", commands = null)
            }
        }
    }

    /**
     * Cleanly shuts down the HTTP client, releasing resources.
     */
    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
