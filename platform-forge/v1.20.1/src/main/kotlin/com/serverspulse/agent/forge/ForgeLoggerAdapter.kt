package com.serverspulse.agent.forge

import com.serverspulse.agent.api.LoggerAdapter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Forge logging adapter using Log4j2 (Forge's standard logging framework).
 */
class ForgeLoggerAdapter : LoggerAdapter {

    private val logger: Logger = LogManager.getLogger("ServersPulse")

    override fun info(message: String) = logger.info(message)
    override fun warn(message: String) = logger.warn(message)
    override fun error(message: String) = logger.error(message)
    override fun error(message: String, throwable: Throwable) = logger.error(message, throwable)
    override fun debug(message: String) = logger.debug(message)
}
