package com.serverspulse.agent.core

/**
 * Resolves the running plugin/mod version from the JAR manifest.
 */
object AgentVersion {
    private const val UNKNOWN_VERSION = "unknown"

    fun resolve(): String {
        val version = AgentRuntime::class.java.`package`?.implementationVersion?.trim().orEmpty()
        return if (version.isNotBlank()) version else UNKNOWN_VERSION
    }
}
