package com.serverspulse.agent.core.collector

import com.serverspulse.agent.api.PlatformAdapter
import com.serverspulse.agent.api.dto.PlayerSessionMetricsPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Accumulates per-session counters for connected players.
 *
 * It is driven off the runtime's existing collection tick rather than a
 * scheduler of its own: a second timer would double the agent's scheduling
 * footprint to measure something that only changes as fast as the tick it is
 * sampled on.
 *
 * [sample] must run on the main server thread, because reading a player's world
 * and latency is not thread-safe on most platforms. [drain] is pure in-memory
 * work and is safe to call from the async sender.
 */
class SessionTracker(
    private val platform: PlatformAdapter,
    private val afkThresholdMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val states = ConcurrentHashMap<String, State>()

    private class State {
        var lastSampleAt: Long = 0L
        var afkMillis: Long = 0L
        /** Whether the platform ever answered the activity question at all. */
        var activityObserved: Boolean = false
        var pingMin: Int? = null
        var pingMax: Int? = null
        var pingTotal: Long = 0L
        var pingSamples: Int = 0
        val worldMillis = mutableMapOf<String, Long>()
        var lastWorld: String? = null
    }

    /**
     * Read every connected player once and fold the elapsed time since their
     * previous sample into their counters.
     *
     * Attributing the whole interval to whatever the player was doing at the
     * end of it is the only option available at this sampling rate, and at a
     * 30-second tick the error is bounded by one interval per state change.
     */
    fun sample() {
        val now = clock()
        val seen = HashSet<String>()

        for (player in platform.players()) {
            val uuid = player.uuid
            seen.add(uuid)

            val state = states.computeIfAbsent(uuid) {
                State().apply { lastSampleAt = now }
            }

            val elapsed = now - state.lastSampleAt
            state.lastSampleAt = now

            if (elapsed > 0) {
                val lastActivity = player.getLastActivityMillis()
                if (lastActivity != null) {
                    state.activityObserved = true
                    if (now - lastActivity >= afkThresholdMillis) {
                        state.afkMillis += elapsed
                    }
                }

                val world = player.currentWorld() ?: state.lastWorld
                if (world != null) {
                    state.worldMillis[world] = (state.worldMillis[world] ?: 0L) + elapsed
                    state.lastWorld = world
                }
            }

            val ping = player.getPing()
            if (ping != null && ping >= 0) {
                state.pingMin = minOf(state.pingMin ?: ping, ping)
                state.pingMax = maxOf(state.pingMax ?: ping, ping)
                state.pingTotal += ping
                state.pingSamples++
            }
        }

        // A player who is no longer listed has left. Their counters were flushed
        // by the leave path; holding the entry any longer would leak memory and
        // would restart their next session with the previous one's totals.
        states.keys.retainAll(seen)
    }

    /** Builds a payload for every player currently being tracked. */
    fun drain(): List<PlayerSessionMetricsPayload> {
        return states.entries.mapNotNull { (uuid, state) -> payloadFor(uuid, state) }
    }

    /**
     * Builds a final payload for one player and forgets them.
     * Called when a player leaves, so their last partial interval is not lost.
     */
    fun finish(uuid: String): PlayerSessionMetricsPayload? {
        val state = states.remove(uuid) ?: return null
        return payloadFor(uuid, state)
    }

    /** Drops all tracked state, for a config reload or shutdown. */
    fun clear() {
        states.clear()
    }

    private fun payloadFor(uuid: String, state: State): PlayerSessionMetricsPayload? {
        val worldSeconds = state.worldMillis
            .mapValues { (_, millis) -> (millis / 1000L).toInt() }
            .filterValues { it > 0 }

        val afkSeconds = (state.afkMillis / 1000L).toInt()
        val pingAvg = if (state.pingSamples > 0) {
            (state.pingTotal / state.pingSamples).toInt()
        } else {
            null
        }

        // Nothing measured means nothing to report. Sending a row of nulls would
        // cost a round trip to tell the backend what it already assumes.
        if (!state.activityObserved && pingAvg == null && worldSeconds.isEmpty()) {
            return null
        }

        return PlayerSessionMetricsPayload(
            playerUuid = uuid,
            // Zero AFK is only reportable when the platform actually answered
            // the activity question; otherwise it would read as "never idle".
            afkSeconds = if (state.activityObserved) afkSeconds else null,
            pingMin = state.pingMin,
            pingAvg = pingAvg,
            pingMax = state.pingMax,
            worldSeconds = worldSeconds.ifEmpty { null }
        )
    }
}
