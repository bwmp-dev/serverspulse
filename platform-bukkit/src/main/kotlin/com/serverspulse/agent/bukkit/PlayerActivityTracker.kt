package com.serverspulse.agent.bukkit

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Records when each player last did something, so the core session tracker can
 * decide who is AFK without knowing anything about Bukkit events.
 *
 * Handlers are MONITOR priority and ignore cancelled events: a movement another
 * plugin cancelled did not happen, and counting it would let a player stay
 * "active" while pushed against a barrier.
 *
 * Head-only movement is deliberately not counted. Looking around is exactly
 * what an AFK-detection bypass macro does, and treating it as activity would
 * make the whole measurement meaningless.
 */
class PlayerActivityTracker : Listener {

    private val lastActivity = ConcurrentHashMap<UUID, Long>()

    fun lastActivityMillis(uuid: UUID): Long? = lastActivity[uuid]

    /** Seeds a player on join so they start as active rather than as unknown. */
    fun onJoin(uuid: UUID) {
        lastActivity[uuid] = System.currentTimeMillis()
    }

    fun forget(uuid: UUID) {
        lastActivity.remove(uuid)
    }

    fun clear() {
        lastActivity.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return
        }
        touch(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        touch(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        touch(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        touch(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        forget(event.player.uniqueId)
    }

    private fun touch(uuid: UUID) {
        lastActivity[uuid] = System.currentTimeMillis()
    }
}
