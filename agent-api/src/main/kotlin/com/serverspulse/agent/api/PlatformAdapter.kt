package com.serverspulse.agent.api

import java.io.File

/**
 * Top-level contract that each platform module must implement.
 * The core runtime accesses all platform functionality through this single entry point.
 */
interface PlatformAdapter {
    /** Platform identifier: e.g. "Paper", "Purpur", "Folia", "Pufferfish", "Spigot", "CraftBukkit", "Fabric". */
    val platformId: String

    /** Minecraft server version string (e.g. "1.21.4"). */
    val mcVersion: String

    /** Feature capabilities for this platform/version combination. */
    val capabilities: VersionCapabilities

    /** Access to the platform's task scheduler. */
    fun scheduler(): SchedulerAdapter

    /** Access to server-level metrics. */
    fun server(): ServerIntrospection

    /** Access to per-world metrics for all loaded worlds. */
    fun worlds(): List<WorldIntrospection>

    /**
     * Access to the currently connected players.
     *
     * Defaults to empty so a platform module that has not implemented player
     * introspection keeps compiling and simply reports nothing.
     */
    fun players(): List<PlayerIntrospection> = emptyList()

    /**
     * Access to state mirrored from other plugins on the server.
     *
     * Defaults to null: a platform with no plugin ecosystem to mirror, and a
     * platform module that has not implemented the integrations, both report
     * nothing and announce no capability.
     */
    fun extensions(): ExtensionSource? = null

    /** Access to the platform's logger. */
    fun logger(): LoggerAdapter

    /**
     * The command players and console use to drive this agent.
     *
     * A proxy MUST NOT claim `serverspulse`. Velocity and BungeeCord match a
     * player's command against the proxy's own registry before forwarding it,
     * so a proxy registering the same name silently swallows every
     * `/serverspulse register` typed in game on a backend server behind it —
     * the admin would bind the proxy while believing they bound the backend.
     */
    val commandLabel: String get() = "serverspulse"

    /** Register plugin commands through the platform. */
    fun registerCommands(registry: CommandRegistry)

    /** The plugin's data folder for config files. */
    fun dataFolder(): File
}
