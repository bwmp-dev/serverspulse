package com.serverspulse.agent.fabric

import java.util.concurrent.ConcurrentHashMap

/**
 * Approximates AFK detection by watching each player's position between samples.
 *
 * Fabric has no event bus for movement that a mixin-free module can subscribe
 * to, so activity is inferred from whether a player moved since the last time
 * they were looked at. The resolution is therefore the agent's collection
 * interval rather than a tick, which is coarse — but the AFK threshold it feeds
 * is measured in minutes, so it is coarse in the direction that does not matter.
 *
 * Head-only rotation is invisible to this, which is the correct behaviour: an
 * anti-AFK macro that only spins the camera should not read as activity.
 */
class FabricPlayerActivityTracker {

    /** Below this, the difference is float noise rather than movement. */
    private val movementEpsilon = 0.05

    private data class Seen(val x: Double, val y: Double, val z: Double, val at: Long)

    private val positions = ConcurrentHashMap<String, Seen>()

    /**
     * Records the player's current position and returns when they last moved.
     *
     * Must be called exactly once per collection sample per player: it is both
     * the observation and the query, because there is no separate event to
     * carry the observation.
     */
    fun observe(uuid: String, x: Double, y: Double, z: Double): Long {
        val now = System.currentTimeMillis()
        val previous = positions[uuid]

        if (previous == null) {
            positions[uuid] = Seen(x, y, z, now)
            return now
        }

        val moved = Math.abs(previous.x - x) > movementEpsilon ||
            Math.abs(previous.y - y) > movementEpsilon ||
            Math.abs(previous.z - z) > movementEpsilon

        val activityAt = if (moved) now else previous.at
        positions[uuid] = Seen(x, y, z, activityAt)
        return activityAt
    }

    fun lastActivityMillis(uuid: String): Long? = positions[uuid]?.at

    /**
     * Forgets everyone not in [connected].
     *
     * Called from the same sampling pass that feeds [observe]; without it a
     * player's last position would outlive their session and the map would grow
     * for the lifetime of the server.
     */
    fun retain(connected: Set<String>) {
        positions.keys.retainAll(connected)
    }
}
