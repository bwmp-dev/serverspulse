package com.serverspulse.agent.core.config

import com.serverspulse.agent.api.LoggerAdapter
import java.nio.file.Files
import kotlin.test.*

class ConfigLoaderTest {
    private val logger = object : LoggerAdapter {
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
        override fun debug(message: String) = Unit
    }

    @Test fun overridesApplyOnFirstLoadAndAreNotPersisted() {
        val folder = Files.createTempDirectory("sp-config").toFile()
        try {
            val env = mapOf("SERVERSPULSE_API_KEY" to "env-key", "SERVERSPULSE_INTERVAL_SECONDS" to "10",
                "SERVERSPULSE_BACKEND_URL" to "http://localhost:8080", "SERVERSPULSE_COLLECT_PLAYER_METRICS" to "false")
            val config = ConfigLoader.load(folder, logger, env, mapOf("serverspulse.api-key" to "property-key"))
            assertEquals("property-key", config.apiKey)
            assertEquals(10, config.intervalSeconds)
            assertEquals("http://localhost:8080", config.backendUrl)
            assertFalse(config.collectPlayerMetrics)
            val privacy = ConfigLoader.load(folder, logger, mapOf("SERVERSPULSE_COLLECT_PLAYER_DATA" to "false", "SERVERSPULSE_HASH_PLAYER_IDENTIFIERS" to "true"), emptyMap())
            assertFalse(privacy.collectPlayerData)
            assertTrue(privacy.hashPlayerIdentifiers)
            assertTrue(ConfigLoader.load(folder, logger, mapOf("SERVERSPULSE_COLLECT_PLAYER_DATA" to "false"), mapOf("serverspulse.collect-player-data" to "true")).collectPlayerData)
            assertFalse(folder.resolve("config.yml").readText().contains("property-key"))
            assertEquals("", ConfigLoader.load(folder, logger, emptyMap(), emptyMap()).apiKey)
            folder.resolve("config.yml").writeText("api-key: file-key\ninterval-seconds: 15")
            assertEquals("file-key", ConfigLoader.load(folder, logger, emptyMap(), emptyMap()).apiKey)
            assertEquals(10, ConfigLoader.load(folder, logger, env, emptyMap()).intervalSeconds)
            assertFailsWith<IllegalArgumentException> {
                ConfigLoader.load(folder, logger, mapOf("SERVERSPULSE_COLLECT_PLAYER_METRICS" to "maybe"), emptyMap())
            }
            assertFailsWith<IllegalArgumentException> {
                ConfigLoader.load(folder, logger, mapOf("SERVERSPULSE_INTERVAL_SECONDS" to "secret-invalid"), emptyMap())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun networkOverrideOrder() {
        val folder = Files.createTempDirectory("sp-network").toFile()
        try {
            folder.resolve("config.yml").writeText("network: yaml-network")
            assertEquals("yaml-network", ConfigLoader.load(folder, logger, emptyMap(), emptyMap()).network)
            val env = mapOf("SERVERSPULSE_NETWORK" to "env-network")
            assertEquals("env-network", ConfigLoader.load(folder, logger, env, emptyMap()).network)
            assertEquals("property-network", ConfigLoader.load(folder, logger, env, mapOf("serverspulse.network" to "property-network")).network)
        } finally { folder.deleteRecursively() }
    }

    @Test fun folderOverrideSelectsConfigLocation() {
        val folder = Files.createTempDirectory("sp-folder").toFile()
        try {
            val target = folder.resolve("actual")
            val config = ConfigLoader.load(folder, logger,
                mapOf("SERVERSPULSE_DATA_FOLDER" to target.path), emptyMap())
            assertTrue(target.resolve("config.yml").exists())
            assertFalse(folder.resolve("config.yml").exists())
            assertEquals("", config.apiKey)
            assertEquals(folder, ConfigLoader.resolveDataFolder(target,
                mapOf("SERVERSPULSE_DATA_FOLDER" to target.path), mapOf("serverspulse.data-folder" to folder.path)))
        } finally { folder.deleteRecursively() }
    }
}
