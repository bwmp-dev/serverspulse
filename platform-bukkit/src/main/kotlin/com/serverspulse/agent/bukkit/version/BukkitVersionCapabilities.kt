package com.serverspulse.agent.bukkit.version

import com.serverspulse.agent.api.VersionCapabilities

/**
 * Resolves version capabilities at startup based on the detected server version and platform.
 * Uses capability flags rather than version-if-chains throughout the codebase.
 */
data class BukkitVersionCapabilities(
    override val supportsMspt: Boolean,
    override val supportsNativeTickTimes: Boolean,
    override val supportsAsyncChunkStats: Boolean,
    override val supportsTpsApi: Boolean,
    override val supportsPlayerPing: Boolean = false,
    override val supportsClientBrand: Boolean = false,
    override val supportsProtocolVersion: Boolean = false,
    override val supportsPlayerWorld: Boolean = true,
    override val supportsActivityTracking: Boolean = true,
    override val supportsPlayerAddress: Boolean = true
) : VersionCapabilities
