package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.LoggerAdapter
import java.util.logging.Level
import java.util.logging.Logger

/**
 * BungeeCord logging adapter wrapping java.util.logging.
 */
class BungeeLoggerAdapter(
    private val logger: Logger,
    var debugEnabled: Boolean = false
) : LoggerAdapter {

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String) {
        logger.warning(message)
    }

    override fun error(message: String) {
        logger.severe(message)
    }

    override fun error(message: String, throwable: Throwable) {
        logger.log(Level.SEVERE, message, throwable)
    }

    override fun debug(message: String) {
        if (debugEnabled) {
            logger.info("[DEBUG] " + message)
        }
    }
}
