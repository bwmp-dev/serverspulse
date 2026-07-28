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
    override val supportsTpsApi: Boolean
) : VersionCapabilities
