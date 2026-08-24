package com.serverspulse.agent.core.collector

import com.serverspulse.agent.api.dto.PlayerSwitchPayload
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * Buffers backend-switch transitions between flushes.
 *
 * Transitions arrive on proxy event threads and are drained by the collection
 * loop, so every access is guarded. The buffer is bounded and drops its oldest
 * entries when full: a proxy whose backend is unreachable would otherwise turn
 * a network outage into an out-of-memory error, and the newest transitions are
 * the ones still worth having.
 */
class NetworkSwitchBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 2000

        /** The backend rejects entries older than this, so holding them is pointless. */
        private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        private val RFC3339 = DateTimeFormatter.ISO_INSTANT
    }

    private val entries = ArrayDeque<Entry>(64)

    private class Entry(
        val playerUuid: String,
        val fromServer: String?,
        val toServer: String?,
        val at: Instant,
        val reason: String
    )

    fun record(
        playerUuid: String,
        fromServer: String?,
        toServer: String?,
        reason: String,
        at: Instant = Instant.now()
    ) {
        val entry = Entry(
            playerUuid = playerUuid,
            fromServer = fromServer?.take(PlayerSwitchPayload.MAX_SERVER_NAME_LENGTH),
            toServer = toServer?.take(PlayerSwitchPayload.MAX_SERVER_NAME_LENGTH),
            at = at,
            reason = reason
        )

        synchronized(entries) {
            while (entries.size >= capacity) {
                entries.pollFirst()
            }
            entries.addLast(entry)
        }
    }

    /** Removes and returns up to [limit] entries, dropping any the backend would reject as stale. */
    fun drain(limit: Int): List<PlayerSwitchPayload> {
        val cutoff = Instant.now().minusMillis(MAX_AGE_MILLIS)
        val drained = ArrayList<PlayerSwitchPayload>(minOf(limit, 64))

        synchronized(entries) {
            while (drained.size < limit) {
                val entry = entries.pollFirst() ?: break
                if (entry.at.isBefore(cutoff)) continue
                drained.add(
                    PlayerSwitchPayload(
                        playerUuid = entry.playerUuid,
                        fromServer = entry.fromServer,
                        toServer = entry.toServer,
                        at = RFC3339.format(entry.at.atOffset(ZoneOffset.UTC)),
                        reason = entry.reason
                    )
                )
            }
        }

        return drained
    }

    val size: Int
        get() = synchronized(entries) { entries.size }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
