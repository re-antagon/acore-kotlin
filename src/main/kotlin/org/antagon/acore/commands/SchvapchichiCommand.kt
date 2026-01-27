package org.antagon.acore.commands

import org.antagon.acore.util.CurseManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin

class SchvapchichiCommand(private val plugin: JavaPlugin, private val curseManager: CurseManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cЭта команда доступна только игрокам!")
            return true
        }

        val player = sender

        // Check permissions
        if (!player.hasPermission("acore.schvapchichi")) {
            player.sendMessage("§cУ вас нет прав для использования этой команды!")
            return true
        }

        // Check if feature is enabled
        if (!plugin.config.getBoolean("schvapchichi.enabled", true)) {
            player.sendMessage("§cЭта фича временно отключена!")
            return true
        }

        // Apply curse to player
        applyCurse(player)

        return true
    }

    private fun applyCurse(player: Player) {
        val playerId = player.uniqueId

        // Add player to permanent curse list
        curseManager.addCursedPlayer(playerId)

        // Mark player as cursed (we'll use metadata for this)
        player.setMetadata("schvapchichi_cursed", FixedMetadataValue(plugin, true))

        // Grant the advancement directly using console command
        plugin.server.dispatchCommand(
            plugin.server.consoleSender,
            "advancement grant ${player.name} only acore:schvapchichi/root"
        )

        // Send curse message
        player.sendMessage("§7Швапчичи заметило вас...")
    }
}
