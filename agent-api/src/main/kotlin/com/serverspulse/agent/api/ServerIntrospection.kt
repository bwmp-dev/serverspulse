package com.serverspulse.agent.api

/**
 * Provides server-level metrics.
 * Implemented per platform, called by the core snapshot assembler.
 */
interface ServerIntrospection {
    /** Current TPS value. Returns 20.0 if the platform cannot report it. */
    fun getTps(): Double

    /** Milliseconds per tick, or null if unsupported on this platform/version. */
    fun getMspt(): Double?

    /** Number of players currently online. */
    fun getOnlinePlayers(): Int

    /** Maximum player slots configured on the server. */
    fun getMaxPlayers(): Int
}
