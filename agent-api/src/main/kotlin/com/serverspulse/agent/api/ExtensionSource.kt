package com.serverspulse.agent.api

import com.serverspulse.agent.api.dto.PlayerExtensionsPayload

/**
 * Mirrors state owned by other plugins on the server — permission groups,
 * economy balances, punishments.
 *
 * Platforms that have no such ecosystem never provide one, which is why
 * [PlatformAdapter.extensions] defaults to null rather than to an empty
 * implementation: "no extension plugins installed" and "this platform cannot
 * host them" are the same absence from the backend's point of view, and
 * neither should announce a capability.
 */
interface ExtensionSource {
    /**
     * Capability names for the integrations that are actually present.
     * Empty when no supported plugin is installed.
     *
     * Unlike [collect] this may be called from any thread, because the agent
     * re-announces its capabilities from background work as well as at startup.
     */
    fun capabilities(): List<String>

    /**
     * Reads a full snapshot. Runs on the main server thread, because every
     * supported integration reads through a main-thread-only plugin API.
     */
    fun collect(): PlayerExtensionsPayload
}
