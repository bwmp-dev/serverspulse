package com.serverspulse.agent.core.geo

import com.serverspulse.agent.api.LoggerAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Resolves a connecting address to a country code without the address ever
 * leaving the machine.
 *
 * The database is DB-IP IP-to-Country Lite, downloaded once per month into the
 * agent's data folder. Downloading rather than bundling keeps the shipped jars
 * small and keeps a stale copy out of every release; a build that has never
 * seen the network simply reports no country, which is the same as the feature
 * being switched off.
 *
 * Nothing here may run on the main server thread: [ensureCurrent] does network
 * and file I/O and takes seconds. [countryCode] is an in-memory binary search
 * and is safe from any thread, including a join handler.
 */
class GeolocationService(
    private val cacheDir: File,
    private val logger: LoggerAdapter,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) }
) {
    companion object {
        private const val DOWNLOAD_BASE = "https://download.db-ip.com/free/dbip-country-lite-"
        private const val DOWNLOAD_SUFFIX = ".csv.gz"
        private const val FILE_PREFIX = "dbip-country-lite-"

        /** The published file is a few megabytes; well past that is not our file. */
        private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

        private val EDITION = DateTimeFormatter.ofPattern("yyyy-MM")

        private const val UNKNOWN_COUNTRY = "ZZ"

        /**
         * Required by the CC-BY-4.0 terms the Lite database is published under.
         * It is logged on every load rather than only on download so it appears
         * in the log of any server actually using the data.
         */
        private const val ATTRIBUTION = "IP geolocation by DB-IP (https://db-ip.com), licensed under CC BY 4.0."
    }

    @Volatile
    private var database: CountryDatabase? = null

    @Volatile
    private var loadedEdition: String? = null

    private val working = AtomicBoolean(false)

    val isReady: Boolean
        get() = database != null

    /**
     * Returns the country [address] is registered in, or null when the database
     * is not loaded or does not list it.
     */
    fun countryCode(address: InetAddress): String? {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isSiteLocalAddress || address.isLinkLocalAddress
        ) {
            return null
        }

        // DB-IP files the reserved and unallocated blocks under ZZ, which is
        // the ISO code for "unknown" rather than a place a player can be in.
        return database?.lookup(address)?.takeIf { it != UNKNOWN_COUNTRY }
    }

    /**
     * Loads the current month's database, downloading it if it is not cached.
     *
     * Returns true when a database is loaded afterwards, including when this
     * call did nothing because one already was. Blocking; never call it from
     * the main server thread.
     */
    fun ensureCurrent(): Boolean {
        val edition = EDITION.format(now())
        if (loadedEdition == edition) return true

        // A monthly refresh that overlaps with the startup load would download
        // and parse the same file twice; whichever call arrives second can just
        // report what the first one has already achieved.
        if (!working.compareAndSet(false, true)) return isReady

        return try {
            load(edition)
        } catch (e: Exception) {
            logger.debug("Geolocation database unavailable: ${e.message}")
            isReady
        } finally {
            working.set(false)
        }
    }

    private fun load(edition: String): Boolean {
        cacheDir.mkdirs()

        // The new month's file is published a little after the month turns, so
        // the previous edition is a legitimate fallback rather than a failure.
        val candidates = listOf(edition, previousEdition())
        for (candidate in candidates) {
            val file = File(cacheDir, FILE_PREFIX + candidate + DOWNLOAD_SUFFIX)
            if (!file.isFile && !download(candidate, file)) continue

            val parsed = parse(file) ?: continue
            database = parsed
            loadedEdition = candidate
            pruneOldEditions(file)
            logger.info(
                "Geolocation enabled: loaded ${parsed.rangeCount} country ranges ($candidate). $ATTRIBUTION"
            )
            return true
        }

        return isReady
    }

    private fun parse(file: File): CountryDatabase? {
        return try {
            file.inputStream().use { raw ->
                GZIPInputStream(raw, 64 * 1024).use { gzip ->
                    BufferedReader(InputStreamReader(gzip, Charsets.UTF_8), 64 * 1024).use { reader ->
                        CountryDatabase.parse(reader).takeIf { it.rangeCount > 0 }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Geolocation database ${file.name} is unreadable, discarding it: ${e.message}")
            file.delete()
            null
        }
    }

    private fun download(edition: String, target: File): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Downloaded beside the target and renamed, so a connection dropped
        // half way through cannot leave a truncated file that looks cached.
        val partial = File(target.parentFile, target.name + ".part")

        return try {
            val request = Request.Builder()
                .url(DOWNLOAD_BASE + edition + DOWNLOAD_SUFFIX)
                .header("User-Agent", "ServersPulse-Agent/1.0")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.debug("Geolocation database $edition not available (HTTP ${response.code}).")
                    return false
                }

                partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    val source = response.body.byteStream()
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw IllegalStateException("database exceeds ${MAX_DOWNLOAD_BYTES / (1024 * 1024)}MB")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }

            target.delete()
            partial.renameTo(target)
        } catch (e: Exception) {
            logger.debug("Geolocation database download failed: ${e.message}")
            partial.delete()
            false
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun pruneOldEditions(keep: File) {
        val files = cacheDir.listFiles() ?: return
        for (file in files) {
            if (file != keep && file.name.startsWith(FILE_PREFIX)) {
                file.delete()
            }
        }
    }

    private fun previousEdition(): String = EDITION.format(now().minusMonths(1))
}
