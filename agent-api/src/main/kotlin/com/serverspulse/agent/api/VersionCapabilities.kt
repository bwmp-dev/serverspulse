package com.serverspulse.agent.api

/**
 * Declares what optional features the current server version supports.
 * Resolved once at startup; collectors read these flags instead of parsing version strings.
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
}
