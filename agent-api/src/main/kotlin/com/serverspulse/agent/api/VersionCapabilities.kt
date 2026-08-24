package com.serverspulse.agent.api

/**
 * Declares what optional features the current server version supports.
 * Resolved once at startup; collectors read these flags instead of parsing version strings.
 *
 * Flags added after the first release carry a default of `false`, so a platform
 * module that has not implemented the feature keeps compiling and correctly
 * reports that it cannot measure it.
 */
interface VersionCapabilities {
    /** Whether the server exposes milliseconds-per-tick (MSPT) natively. */
    val supportsMspt: Boolean

    /** Whether native tick-time arrays are available (e.g. Paper's getTickTimes). */
    val supportsNativeTickTimes: Boolean

    /** Whether async chunk statistics are available. */
    val supportsAsyncChunkStats: Boolean

    /** Whether the TPS array is available via Bukkit.getTPS() or equivalent. */
    val supportsTpsApi: Boolean

    /** Whether per-player latency can be read. */
    val supportsPlayerPing: Boolean get() = false

    /** Whether the client brand from the `minecraft:brand` channel is readable. */
    val supportsClientBrand: Boolean get() = false

    /** Whether the handshake protocol number is readable. */
    val supportsProtocolVersion: Boolean get() = false

    /** Whether the world a player is currently in can be read. */
    val supportsPlayerWorld: Boolean get() = false

    /** Whether movement and interaction are tracked well enough to detect AFK. */
    val supportsActivityTracking: Boolean get() = false

    /**
     * Whether a joining player's network address is reachable.
     *
     * Gates geolocation: an agent that cannot see an address would otherwise
     * announce the capability and then never send a country code.
     */
    val supportsPlayerAddress: Boolean get() = false

    /** Whether backend-switch transitions can be observed, i.e. this is a proxy. */
    val supportsNetworkSwitches: Boolean get() = false
}
