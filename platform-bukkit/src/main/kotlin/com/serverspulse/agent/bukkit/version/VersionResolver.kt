package com.serverspulse.agent.bukkit.version

import org.bukkit.Bukkit

/**
 * Detects the server platform and Minecraft version,
 * then resolves the set of capabilities available at runtime.
 *
 * This is the single place where version detection happens.
 * All other code reads [BukkitVersionCapabilities] flags.
 *
 * Baseline: compiled against Spigot API 1.8.8.
 * All features added after 1.8 are detected via reflection at startup.
 *
 * Detection order matters: Paper forks (Purpur, Folia, Pufferfish, etc.)
 * include "paper" in their version strings, so specific forks are checked
 * before the generic "Paper" fallback.
 */
object VersionResolver {

    /**
     * Known Paper-derived forks. Used by [resolveCapabilities] to determine
     * whether Paper-specific APIs (MSPT, async chunks, native tick times) are available.
     */
    private val PAPER_FORKS = setOf("Folia", "Purpur", "Pufferfish", "Airplane", "Paper")

    data class ServerInfo(
        val platformId: String,      // e.g. "Purpur", "Folia", "Paper", "Spigot", "CraftBukkit"
        val mcVersion: String,       // e.g. "1.21.4"
        val majorMinor: IntArray,    // e.g. [1, 21, 4]
    )

    fun detectServer(): ServerInfo {
        val versionString = Bukkit.getVersion() // e.g. "git-Paper-123 (MC: 1.21.4)"
        val brandName = Bukkit.getName().lowercase()
        val versionLower = versionString.lowercase()

        // Order matters: check specific Paper forks before generic "Paper",
        // since forks typically contain "paper" in their version strings too.
        val platformId = when {
            isFoliaRuntime(brandName, versionLower) -> "Folia"
            brandName.contains("purpur")     || versionLower.contains("purpur")     -> "Purpur"
            brandName.contains("pufferfish") || versionLower.contains("pufferfish") -> "Pufferfish"
            brandName.contains("airplane")   || versionLower.contains("airplane")   -> "Airplane"
            brandName.contains("paper")      || versionLower.contains("paper")      -> "Paper"
            brandName.contains("spigot")     || versionLower.contains("spigot")     -> "Spigot"
            else -> "CraftBukkit"
        }

        val mcVersion = extractMcVersion(Bukkit.getBukkitVersion()) // "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
        val parts = parseMcVersion(mcVersion)

        return ServerInfo(platformId, mcVersion, parts)
    }

    fun resolveCapabilities(info: ServerInfo): BukkitVersionCapabilities {
        val isPaperBased = info.platformId in PAPER_FORKS
        val minor = info.majorMinor.getOrElse(1) { 0 }  // MC minor version (e.g. 21 in 1.21.4)

        // Bukkit.getTPS() / Server.getTPS():
        //   Not present on CraftBukkit.
        //   Added to Spigot API somewhere around 1.9-1.11.
        //   Always present on Paper and its forks.
        // We detect it at runtime via reflection rather than guessing from version.
        val supportsTpsApi = hasTpsMethod()

        // MSPT: Paper provides getAverageTickTime() starting around 1.19.
        // All Paper forks inherit this API.
        // Verified via reflection in case future Spigot versions add it too.
        val supportsMspt = hasMsptMethod()

        // Native tick times array: Paper 1.19+ (and forks)
        val supportsNativeTickTimes = isPaperBased && minor >= 19

        // Async chunk stats: Paper 1.13+ (and forks)
        val supportsAsyncChunkStats = isPaperBased && minor >= 13

        return BukkitVersionCapabilities(
            supportsMspt = supportsMspt,
            supportsNativeTickTimes = supportsNativeTickTimes,
            supportsAsyncChunkStats = supportsAsyncChunkStats,
            supportsTpsApi = supportsTpsApi
        )
    }

    /**
     * Check if getTPS() exists on the Server instance.
     * Works regardless of the compile-time API by reflecting on the runtime class.
     */
    private fun hasTpsMethod(): Boolean {
        return try {
            Bukkit.getServer()::class.java.getMethod("getTPS")
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if getAverageTickTime() exists on the Server instance (Paper 1.19+).
     */
    private fun hasMsptMethod(): Boolean {
        return try {
            Bukkit.getServer()::class.java.getMethod("getAverageTickTime")
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts the clean MC version from Bukkit's version string.
     * "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
     */
    private fun extractMcVersion(bukkitVersion: String): String {
        return bukkitVersion.split("-").firstOrNull() ?: bukkitVersion
    }

    /**
     * Detect Folia and Folia-based forks.
     *
     * Some forks do not expose "folia" in their version string, so we also
     * check for Folia's runtime marker class.
     */
    private fun isFoliaRuntime(brandName: String, versionLower: String): Boolean {
        if (brandName.contains("folia") || versionLower.contains("folia")) {
            return true
        }

        return try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Parses "1.21.4" into [1, 21, 4].
     */
    private fun parseMcVersion(version: String): IntArray {
        return version.split(".")
            .mapNotNull { it.toIntOrNull() }
            .toIntArray()
    }
}
