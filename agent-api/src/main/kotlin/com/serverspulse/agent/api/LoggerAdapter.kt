package com.serverspulse.agent.api

/**
 * Thin abstraction over the platform's logging system.
 */
interface LoggerAdapter {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
    fun error(message: String, throwable: Throwable)
    fun debug(message: String)
}
