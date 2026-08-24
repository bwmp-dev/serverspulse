package com.serverspulse.agent.extensions

import com.serverspulse.agent.api.dto.PunishmentEntry
import com.serverspulse.agent.api.dto.PunishmentTypes
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Mirrors active mutes from LiteBans and Essentials.
 *
 * Only mutes, and only for online players. Neither plugin exposes an enumerable
 * punishment history through a public API — LiteBans keeps its records in its
 * own database and answers only "is this player muted right now", and Essentials
 * answers the same question per user — so a ban cannot be observed here at all:
 * a banned player is by definition not connected.
 *
 * Because neither API reports when a mute was issued, [issuedAt] is the first
 * moment this agent saw it. That is an honest observation rather than a guess,
 * and it is stable for as long as the agent keeps running; an agent restart
 * re-dates a mute it is still watching, which is the cost of the API not
 * carrying the real date.
 *
 * A mute that disappears is reported once more with `active = false`, otherwise
 * the backend would hold it open forever — the plugins offer no removal event
 * this module can subscribe to.
 */
internal class PunishmentMirror {

    private companion object {
        const val SOURCE_LITEBANS = "litebans"
        const val SOURCE_ESSENTIALS = "essentials"

        val RFC3339: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }

    private val liteBans = LiteBansHandles()
    private val essentials = EssentialsHandles()

    /** First-seen time and source of every mute currently reported as active. */
    private val observed = HashMap<String, Observation>()

    val isAvailable: Boolean
        get() = liteBans.resolve() != null || essentials.resolve() != null

    /**
     * Only players who are connected are examined, so a mute is deactivated
     * when the player is seen without it — not when they log off. A mute lifted
     * while its owner is offline stays open until they next connect, because
     * neither plugin offers an event that would say otherwise.
     */
    fun collect(players: Collection<Player>): List<PunishmentEntry> {
        val now = Instant.now()
        val entries = ArrayList<PunishmentEntry>()

        for (player in players) {
            val key = player.uniqueId.toString()
            val mute = essentials.mute(player) ?: liteBans.mute(player)
            val previous = observed[key]

            if (mute == null) {
                observed.remove(key)?.let { entries.add(deactivated(key, it)) }
                continue
            }

            // A mute that changed hands between plugins is a different record,
            // so the old one is closed rather than silently rewritten.
            val observation = if (previous != null && previous.source == mute.source) {
                previous
            } else {
                previous?.let { entries.add(deactivated(key, it)) }
                Observation(mute.source, now).also { observed[key] = it }
            }

            entries.add(
                PunishmentEntry(
                    playerUuid = key,
                    type = if (mute.expiresAt != null) PunishmentTypes.TEMPMUTE else PunishmentTypes.MUTE,
                    reason = mute.reason,
                    issuedAt = RFC3339.format(observation.firstSeen.atOffset(ZoneOffset.UTC)),
                    expiresAt = mute.expiresAt?.let { RFC3339.format(it.atOffset(ZoneOffset.UTC)) },
                    active = true,
                    source = mute.source,
                    sourceId = key
                )
            )
        }

        return entries
    }

    private fun deactivated(playerUuid: String, observation: Observation) = PunishmentEntry(
        playerUuid = playerUuid,
        type = PunishmentTypes.MUTE,
        issuedAt = RFC3339.format(observation.firstSeen.atOffset(ZoneOffset.UTC)),
        active = false,
        source = observation.source,
        sourceId = playerUuid
    )

    private class Observation(val source: String, val firstSeen: Instant)

    private class Mute(val source: String, val reason: String?, val expiresAt: Instant?)

    /**
     * LiteBans exposes only a boolean check, so a mute observed through it
     * carries no reason and no expiry — a permanent and a timed mute are
     * indistinguishable from here.
     */
    private class LiteBansHandles {
        private var database: Any? = null
        private var isMuted: Method? = null

        fun resolve(): Any? {
            database?.let { return it }

            val type = Reflect.type("litebans.api.Database") ?: return null
            val instance = Reflect.invoke(Reflect.method(type, "get"), null) ?: return null
            val method = Reflect.method(type, "isPlayerMuted", UUID::class.java, String::class.java) ?: return null

            isMuted = method
            database = instance
            return instance
        }

        fun mute(player: Player): Mute? {
            val instance = resolve() ?: return null
            val muted = Reflect.invoke(isMuted, instance, player.uniqueId, null) as? Boolean ?: return null
            return if (muted) Mute(SOURCE_LITEBANS, reason = null, expiresAt = null) else null
        }
    }

    /**
     * Essentials carries the reason and the timeout, so its mutes are the
     * richer of the two and are preferred when both plugins are installed.
     */
    private class EssentialsHandles {
        private var plugin: Any? = null
        private var getUser: Method? = null
        private var isMuted: Method? = null
        private var muteTimeout: Method? = null
        private var muteReason: Method? = null

        fun resolve(): Any? {
            plugin?.let { return it }

            val instance = try {
                Bukkit.getPluginManager().getPlugin("Essentials")
            } catch (_: Throwable) {
                null
            } ?: return null
            if (!instance.isEnabled) return null

            val essentialsType = Reflect.type("com.earth2me.essentials.Essentials") ?: return null
            if (!essentialsType.isInstance(instance)) return null

            val userType = Reflect.type("com.earth2me.essentials.User") ?: return null
            val lookup = Reflect.method(essentialsType, "getUser", UUID::class.java) ?: return null
            val muted = Reflect.method(userType, "isMuted") ?: return null

            getUser = lookup
            isMuted = muted
            // Present on EssentialsX, absent on the older Essentials line.
            muteTimeout = Reflect.method(userType, "getMuteTimeout")
            muteReason = Reflect.method(userType, "getMuteReason")
            plugin = instance
            return instance
        }

        fun mute(player: Player): Mute? {
            val instance = resolve() ?: return null
            val user = Reflect.invoke(getUser, instance, player.uniqueId) ?: return null
            val muted = Reflect.invoke(isMuted, user) as? Boolean ?: return null
            if (!muted) return null

            // Essentials uses 0 for "no timeout", which is a permanent mute.
            val timeout = (Reflect.invoke(muteTimeout, user) as? Long)?.takeIf { it > 0L }
            val reason = (Reflect.invoke(muteReason, user) as? String)?.takeIf { it.isNotBlank() }

            return Mute(SOURCE_ESSENTIALS, reason, timeout?.let { Instant.ofEpochMilli(it) })
        }
    }
}
