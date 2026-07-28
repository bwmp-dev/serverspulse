package com.serverspulse.agent.api

/**
 * Registry for plugin commands.
 * The platform module registers commands through this interface.
 */
interface CommandRegistry {
    /**
     * Registers a command handler with the given name.
     * @param name the command name (e.g. "serverspulse")
     * @param handler handles execution of the command
     */
    fun register(name: String, handler: CommandHandler)
}

/**
 * Handles execution of a plugin command.
 */
interface CommandHandler {
    /**
     * Execute the command.
     * @param sender abstracted sender (player or console)
     * @param args the command arguments
     * @return true if the command was handled
     */
    fun execute(sender: CommandSender, args: Array<String>): Boolean
}

/**
 * Abstraction over the entity executing a command.
 */
interface CommandSender {
    fun sendMessage(message: String)
    fun hasPermission(permission: String): Boolean
    val isConsole: Boolean
    val isOp: Boolean
}
