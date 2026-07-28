package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.ServerIntrospection
import com.serverspulse.agent.api.VersionCapabilities
import org.bukkit.Bukkit
import java.lang.reflect.Method

/**
 * Server-level metric collection for Bukkit/Spigot/Paper.
 * Uses capability flags to gate access to version-specific APIs.
 *
 * TPS and MSPT are accessed via reflection to compile against the 1.8.8 baseline API
 * while supporting modern features at runtime (compile-low, detect-high strategy).
 *
 * On servers without `getTPS()` (pre-1.9 Spigot, CraftBukkit), falls back to a
 * [TickCounter] that measures TPS by timing tick intervals.
 */
class BukkitServerIntrospection(
    private val capabilities: VersionCapabilities,
    private val tickCounter: TickCounter?
) : ServerIntrospection {

    // Cache reflected methods at construction time to avoid repeated lookups
    private val tpsMethod: Method? = resolveTpsMethod()
    private val msptMethod: Method? = resolveMsptMethod()

    override fun getTps(): Double {
        // Fast path: server exposes getTPS() natively (Spigot 1.9+, Paper)
        if (capabilities.supportsTpsApi && tpsMethod != null) {
            return try {
                val tpsArray = tpsMethod.invoke(Bukkit.getServer()) as? DoubleArray
                tpsArray?.firstOrNull()?.coerceIn(0.0, 20.0) ?: fallbackTps()
            } catch (e: Exception) {
                fallbackTps()
            }
        }

        // Slow path: use the tick counter
        return fallbackTps()
    }

    override fun getMspt(): Double? {
        if (!capabilities.supportsMspt || msptMethod == null) return null

        return try {
            msptMethod.invoke(Bukkit.getServer()) as? Double
        } catch (e: Exception) {
            null
        }
    }

    override fun getOnlinePlayers(): Int {
        return Bukkit.getOnlinePlayers().size
    }

    override fun getMaxPlayers(): Int {
        return Bukkit.getMaxPlayers()
    }

    private fun fallbackTps(): Double {
        return tickCounter?.getTps() ?: 20.0
    }

    private fun resolveTpsMethod(): Method? {
        return try {
            Bukkit.getServer()::class.java.getMethod("getTPS")
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMsptMethod(): Method? {
        return try {
            Bukkit.getServer()::class.java.getMethod("getAverageTickTime")
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
