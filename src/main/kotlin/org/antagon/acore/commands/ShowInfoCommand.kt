package org.antagon.acore.commands

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.PermissionNode
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
        val hasPermission = player.hasPermission("bossbar.show")

        val luckPerms = LuckPermsProvider.get()
        val user = luckPerms.userManager.getUser(player.uniqueId)
        if (user == null) {
            player.sendMessage("§cОшибка: данные LuckPerms еще не загружены!")
            return true
        }

        if (hasPermission) {
            val node = PermissionNode.builder("bossbar.show").value(false).build()
            user.data().add(node)
            luckPerms.userManager.saveUser(user)
            player.sendMessage("§cBossbar отключен!")
        } else {
            val node = PermissionNode.builder("bossbar.show").value(true).build()
            user.data().add(node)
            luckPerms.userManager.saveUser(user)
            player.sendMessage("§aBossbar включен!")
        }

        return true
    }
}