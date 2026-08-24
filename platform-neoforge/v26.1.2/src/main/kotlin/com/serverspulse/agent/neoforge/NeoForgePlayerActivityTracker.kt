package com.serverspulse.agent.neoforge

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Approximates AFK detection by watching each player's position between samples.
 *
 * NeoForge has no movement event this mixin-free module can subscribe to, so
 * activity is inferred from whether a player moved since they were last looked
 * at. The resolution is the agent's collection interval rather than a tick,
 * which is coarse — but the AFK threshold it feeds is measured in minutes.
 *
 * Head-only rotation is invisible to this, which is the correct behaviour: a
 * camera-spinning anti-AFK macro should not read as activity.
 */
class NeoForgePlayerActivityTracker {

    /** Below this, the difference is float noise rather than movement. */
    private val movementEpsilon = 0.05

    private data class Seen(val x: Double, val y: Double, val z: Double, val at: Long)

    private val positions = ConcurrentHashMap<String, Seen>()

    /**
     * Records the player's current position and returns when they last moved.
     * Must be called exactly once per collection sample per player.
     */
    fun observe(uuid: String, x: Double, y: Double, z: Double): Long {
        val now = System.currentTimeMillis()
        val previous = positions[uuid]

        if (previous == null) {
            positions[uuid] = Seen(x, y, z, now)
            return now
        }

        val moved = abs(previous.x - x) > movementEpsilon ||
            abs(previous.y - y) > movementEpsilon ||
            abs(previous.z - z) > movementEpsilon

        val activityAt = if (moved) now else previous.at
        positions[uuid] = Seen(x, y, z, activityAt)
        return activityAt
    }

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
