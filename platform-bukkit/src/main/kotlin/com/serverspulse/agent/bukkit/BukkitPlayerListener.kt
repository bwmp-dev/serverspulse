package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.VersionCapabilities
import com.serverspulse.agent.core.AgentRuntime
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Bukkit event listener that hooks into player join/leave events and forwards
 * them to the [AgentRuntime] for transmission to the backend.
 *
 * [PlayerLoginEvent] (MONITOR priority, post-allow) is used for joins because
 * it provides [PlayerLoginEvent.getHostname], the virtual hostname the player
 * used to connect — unavailable on the later [PlayerJoinEvent].
 * Only ALLOWED logins are forwarded (kicks are ignored).
 *
 * [PlayerQuitEvent] fires reliably for all disconnects.
 *
 * Both handlers dispatch the network call asynchronously so the main thread
 * is never blocked by HTTP I/O.
 */
class BukkitPlayerListener(
    private val runtime: AgentRuntime,
    private val schedulerAdapter: BukkitSchedulerAdapter,
    private val capabilities: VersionCapabilities,
    private val activityTracker: PlayerActivityTracker
) : Listener {

    private companion object {
        /** Two seconds, in ticks. See onPlayerLogin for why it is not sooner. */
        const val BRAND_READ_DELAY_TICKS = 40L
    }

    /**
     * Player joined — send a "join" event with the hostname they connected to.
     * MONITOR priority so we run after other plugins have had a chance to
     * allow/deny the login; we skip denied logins entirely.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onPlayerLogin(event: PlayerLoginEvent) {
        // Only track players that were actually allowed onto the server.
        if (event.result != PlayerLoginEvent.Result.ALLOWED) return

        val player   = event.player
        val uuid     = player.uniqueId.toString()
        val username = player.name
        val hostname = event.hostname.takeIf { it.isNotBlank() }
        // Handed to the runtime purely so it can derive a country code locally.
        // It is never sent, stored or logged; see AgentRuntime.onPlayerJoin.
        val address = event.address

        activityTracker.onJoin(player.uniqueId)

        // The client sends its brand on a plugin channel shortly after it
        // finishes joining, which is well after PlayerLoginEvent. Reading it on
        // the next tick returns null for every player; two seconds is late
        // enough for the message to have arrived and still early enough that
        // the join event describes the join.
        schedulerAdapter.runLater(BRAND_READ_DELAY_TICKS, Runnable {
            val introspection = BukkitPlayerIntrospection(player, capabilities, activityTracker)
            val clientBrand = introspection.getClientBrand()
            val protocolVersion = introspection.getProtocolVersion()

            schedulerAdapter.runAsync(Runnable {
                runtime.onPlayerJoin(
                    playerUuid = uuid,
                    username = username,
                    hostname = hostname,
                    clientBrand = clientBrand,
                    protocolVersion = protocolVersion,
                    address = address
                )
            })
        })
    }

    /**
     * Player left — send a "leave" event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid     = event.player.uniqueId.toString()
        val username = event.player.name

        schedulerAdapter.runAsync(Runnable {
            runtime.onPlayerLeave(uuid, username)
        })
    }
}
