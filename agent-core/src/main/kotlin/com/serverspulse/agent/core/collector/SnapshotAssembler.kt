package com.serverspulse.agent.core.collector

import com.serverspulse.agent.api.PlatformAdapter
import com.serverspulse.agent.api.dto.ServerSnapshot
import com.serverspulse.agent.api.dto.WorldSnapshot
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Assembles a [ServerSnapshot] by reading from the [PlatformAdapter] and [SystemMetrics].
 * This runs on the main server thread to safely access Bukkit/Fabric APIs.
 */
class SnapshotAssembler(private val platform: PlatformAdapter) {

    companion object {
        private val RFC3339 = DateTimeFormatter.ISO_INSTANT
    }

    /**
     * Collect all metrics and build a snapshot.
     * Must be called from the server's main thread (world access is not thread-safe).
     */
    fun assemble(): ServerSnapshot {
        val server = platform.server()
        val worlds = platform.worlds()

        val worldSnapshots = worlds.map { w ->
            WorldSnapshot(
                name = w.name,
                dimension = w.dimension,
                entityCount = w.getEntityCount(),
                loadedChunks = w.getLoadedChunks(),
                playerCount = w.getPlayerCount()
            )
        }

        return ServerSnapshot(
            timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(RFC3339),
            tps = server.getTps(),
            mspt = server.getMspt(),
            memoryUsedMB = SystemMetrics.memoryUsedMB(),
            memoryMaxMB = SystemMetrics.memoryMaxMB(),
            gcPauseMs = SystemMetrics.gcPauseDeltaMs(),
            onlinePlayers = server.getOnlinePlayers(),
            maxPlayers = server.getMaxPlayers(),
            platform = platform.platformId,
            mcVersion = platform.mcVersion,
            worlds = worldSnapshots
        )
    }
}
