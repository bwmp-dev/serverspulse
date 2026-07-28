package com.serverspulse.agent.api

/**
 * Provides per-world metrics for a single loaded world.
 */
interface WorldIntrospection {
    /** The world name (e.g. "world", "world_nether"). */
    val name: String

    /** The dimension type identifier (e.g. "overworld", "the_nether", "the_end"). */
    val dimension: String

    /** Total number of entities loaded in this world. */
    fun getEntityCount(): Int

    /** Number of currently loaded chunks. */
    fun getLoadedChunks(): Int

    /** Number of players currently in this world. */
    fun getPlayerCount(): Int
}
