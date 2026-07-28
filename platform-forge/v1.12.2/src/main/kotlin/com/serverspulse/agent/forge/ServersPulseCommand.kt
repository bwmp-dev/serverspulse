package com.serverspulse.agent.forge

import net.minecraft.command.CommandBase
import net.minecraft.command.ICommandSender
import net.minecraft.server.MinecraftServer

/**
 * The `/serverspulse` command for MC 1.12.2 using the legacy [CommandBase] API.
 */
class ServersPulseCommand(private val mod: ForgeServersPulse) : CommandBase() {

    override fun getName(): String = "serverspulse"

    override fun getUsage(sender: ICommandSender): String = "/serverspulse [register <code>|reload|status]"

    override fun getAliases(): MutableList<String> = mutableListOf("sp", "pulse")

    override fun getRequiredPermissionLevel(): Int = 4

    override fun execute(server: MinecraftServer, sender: ICommandSender, args: Array<String>) {
        mod.executeCommand(sender, args)
    }
}
