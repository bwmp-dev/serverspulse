package com.serverspulse.agent.api

/**
 * Read-only view of one connected player, used by the core session tracker.
 *
 * Every measurement is optional and defaults to "not available". A platform
 * implements only what its API and Minecraft version actually expose, and the
 * six Forge modules can inherit the defaults without a line of change. The core
 * runtime reports the difference to the backend as a capability, so the
 * dashboard can say "your agent build does not measure this" rather than
 * drawing a chart of zeroes.
 */
interface PlayerIntrospection {
    /** Minecraft UUID string, with dashes. */
    val uuid: String

    /** Current in-game name. */
    val name: String

    /** Round-trip latency in milliseconds, or null if unsupported. */
    fun getPing(): Int? = null

    /**
     * Identifier of the world the player is currently in, or null if unsupported.
     *
     * This is deliberately the platform's own notion of world identity rather
     * than a normalised dimension key. Bukkit reports the world name ("world",
     * "resource_world"), because a multiworld server can run several distinct
     * worlds on the same dimension type and collapsing them onto
     * "minecraft:overworld" would merge places players think of as separate.
     * Fabric and NeoForge report the dimension key, because that is the only
     * identity a world has there.
     *
     * The consequence is that the same physical world reads differently across
     * platforms, so world-time series are comparable within a server but not
     * between servers on different platforms.
     */
    fun currentWorld(): String? = null

    /** Client brand from the `minecraft:brand` channel, or null if unsupported. */
    fun getClientBrand(): String? = null

    /** Handshake protocol number, or null if unsupported. */
    fun getProtocolVersion(): Int? = null

    /**
     * Epoch milliseconds of the player's last movement or interaction.
     *
     * AFK detection lives platform-side because only the platform sees the
     * relevant events; the core runtime only compares this against its
     * configured threshold, which keeps it free of platform APIs.
     */
    fun getLastActivityMillis(): Long? = null
}
