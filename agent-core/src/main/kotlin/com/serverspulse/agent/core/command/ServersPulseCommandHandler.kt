package com.serverspulse.agent.core.command

import com.serverspulse.agent.api.CommandHandler
import com.serverspulse.agent.api.CommandSender
import com.serverspulse.agent.core.AgentRuntime

/**
 * Shared command handler for `/serverspulse` (and aliases `/sp`, `/pulse`).
 *
 * Lives in agent-core so every platform reuses the same logic.
 * Platforms only need to wire their native command system to call
 * [execute] with an adapted [CommandSender].
 *
 * All subcommands require OP. Console is always allowed.
 */
class ServersPulseCommandHandler(private val runtime: AgentRuntime) : CommandHandler {

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.isConsole && !sender.isOp) {
            sender.sendMessage("[ServersPulse] You must be a server operator to use this command.")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> handleReload(sender)
            "status" -> handleStatus(sender)
            "register" -> handleRegister(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun handleReload(sender: CommandSender) {
        val result = runtime.reload()
        if (result.success) {
            sender.sendMessage("[ServersPulse] Configuration reloaded successfully.")
        } else {
            for (error in result.errors) {
                sender.sendMessage("[ServersPulse] Error: $error")
            }
        }
    }

    private fun handleStatus(sender: CommandSender) {
        val status = runtime.status()
        sender.sendMessage("[ServersPulse] Agent: ${if (status.running) "running" else "stopped"}")
        sender.sendMessage("[ServersPulse] Platform: ${status.platform} (MC ${status.mcVersion})")
        sender.sendMessage("[ServersPulse] Interval: ${status.intervalSeconds}s")
        sender.sendMessage("[ServersPulse] Backend: ${status.backendUrl}")
    }

    private fun handleRegister(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            sender.sendMessage("[ServersPulse] Usage: /serverspulse register <code>")
            sender.sendMessage("[ServersPulse] Get a registration code from the ServersPulse dashboard.")
            return
        }
        runtime.register(args[1], sender)
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("[ServersPulse] Commands:")
        sender.sendMessage("  /serverspulse register <code> - Register with a one-time code")
        sender.sendMessage("  /serverspulse reload - Reload configuration")
        sender.sendMessage("  /serverspulse status - Show agent status")
    }
}
