package com.serverspulse.agent.api

/**
 * Provides server-level metrics.
 * Implemented per platform, called by the core snapshot assembler.
 */
interface ServerIntrospection {
    /**
     * Current TPS value, or null on a platform that has no tick loop to
     * measure — a proxy being the only such case today. Null is reported as
     * "not measured" rather than being rounded up to a healthy 20.
     */
    fun getTps(): Double?

    /** Milliseconds per tick, or null if unsupported on this platform/version. */
    fun getMspt(): Double?

    /** Number of players currently online. */
    fun getOnlinePlayers(): Int

    /** Maximum player slots configured on the server. */
    fun getMaxPlayers(): Int
}
