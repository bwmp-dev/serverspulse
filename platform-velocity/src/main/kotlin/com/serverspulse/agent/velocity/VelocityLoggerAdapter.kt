package com.serverspulse.agent.velocity

import com.serverspulse.agent.api.LoggerAdapter
import org.slf4j.Logger

/**
 * Velocity logging adapter wrapping the SLF4J logger the proxy injects.
 */
class VelocityLoggerAdapter(private val logger: Logger) : LoggerAdapter {

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String) {
        logger.warn(message)
    }

    override fun error(message: String) {
        logger.error(message)
    }

    override fun error(message: String, throwable: Throwable) {
        logger.error(message, throwable)
    }

    override fun debug(message: String) {
        logger.debug(message)
    }
}
