package org.antagon.acore.commands

import org.antagon.acore.util.CurseManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class AntiSchvapchichiCommand(private val plugin: JavaPlugin, private val curseManager: CurseManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cЭта команда доступна только игрокам!")
            return true
        }

        val player = sender

        // Check permissions
        if (!player.hasPermission("acore.a_schvapchichi")) {
            player.sendMessage("§cУ вас нет прав для использования этой команды!")
            return true
        }

        // Check if feature is enabled
        if (!plugin.config.getBoolean("schvapchichi.enabled", true)) {
            player.sendMessage("§cЭта фича временно отключена!")
            return true
        }

        // Remove curse from player
        removeCurse(player)

        return true
    }

    private fun removeCurse(player: Player) {
        val playerId = player.uniqueId

        // Remove player from permanent curse list
        curseManager.removeCursedPlayer(playerId)

        // Remove metadata curse
        if (player.hasMetadata("schvapchichi_cursed")) {
            player.removeMetadata("schvapchichi_cursed", plugin)
        }

        // Send success message
        player.sendMessage("§aВы избавились от проклятья Швапчичи!")
        player.sendMessage("§7Теперь ваши предметы в безопасности.")
    }
}