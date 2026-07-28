package com.serverspulse.agent.core.command

import com.serverspulse.agent.api.LoggerAdapter
import com.serverspulse.agent.api.dto.CommandPayload

/**
 * Dispatches commands received from the backend ingest response.
 * Each command type has a registered handler.
 */
class CommandDispatcher(private val logger: LoggerAdapter) {

    private val handlers = mutableMapOf<String, CommandExecutor>()

    fun register(type: String, executor: CommandExecutor) {
        handlers[type] = executor
    }

    /**
     * Process a list of commands from the backend.
     * Called from the async transport thread.
     */
    fun dispatch(commands: List<CommandPayload>) {
        handlers.values
            .distinct()
            .filterIsInstance<CommandBatchAware>()
            .forEach { observer ->
                try {
                    observer.onCommandBatch(commands)
                } catch (e: Exception) {
                    logger.error("Error handling command batch", e)
                }
            }

        for (cmd in commands) {
            val handler = handlers[cmd.type]
            if (handler == null) {
                logger.debug("Unknown command type: ${cmd.type}")
                continue
            }
            try {
                handler.execute(cmd)
            } catch (e: Exception) {
                logger.error("Error executing command '${cmd.type}'", e)
            }
        }
    }

    fun shutdown() {
        handlers.values
            .distinct()
            .filterIsInstance<CommandShutdownAware>()
            .forEach { handler ->
                try {
                    handler.onCommandShutdown()
                } catch (e: Exception) {
                    logger.error("Error shutting down command handler", e)
                }
            }
    }
}

/**
 * Executes a specific type of backend command.
 */
interface CommandExecutor {
    fun execute(command: CommandPayload)
}

/**
 * Optional hook for handlers that need visibility into each full command poll.
 */
interface CommandBatchAware {
    fun onCommandBatch(commands: List<CommandPayload>)
}

interface CommandShutdownAware {
    fun onCommandShutdown()
}
