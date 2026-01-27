package org.antagon.acore.commands

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class ShowInfoCommand : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cЭта команда доступна только игрокам!")
            return true
        }

        val player = sender
        val playerName = player.name
        val hasPermission = player.hasPermission("bossbar.show")

        if (hasPermission) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user $playerName permission set bossbar.show false")
            player.sendMessage("§cBossbar отключен!")
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user $playerName permission set bossbar.show true")
            player.sendMessage("§aBossbar включен!")
        }

        return true
    }
}