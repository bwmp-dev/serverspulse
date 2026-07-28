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

    /** Access to the platform's logger. */
    fun logger(): LoggerAdapter

    /** Register plugin commands through the platform. */
    fun registerCommands(registry: CommandRegistry)

    /** The plugin's data folder for config files. */
    fun dataFolder(): File
}
