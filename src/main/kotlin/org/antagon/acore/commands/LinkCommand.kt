package org.antagon.acore.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.antagon.acore.util.ReferralManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class LinkCommand(private val plugin: JavaPlugin, private val referralManager: ReferralManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cЭта команда доступна только игрокам!")
            return true
        }

        val inviter = sender

        // Check permissions
        if (!inviter.hasPermission("acore.link")) {
            inviter.sendMessage("§cУ вас нет прав для использования этой команды!")
            return true
        }

        // Check if feature is enabled
        if (!plugin.config.getBoolean("referrals.enabled", true)) {
            inviter.sendMessage("§cЭта фича временно отключена!")
            return true
        }

        if (args.size != 1) {
            inviter.sendMessage("§cИспользование: /link <ник_реферала>")
            return true
        }

        val referralName = args[0]
        val referral = Bukkit.getPlayer(referralName)

        if (referral == null) {
            inviter.sendMessage("§cИгрок $referralName не онлайн!")
            return true
        }

        if (referral == inviter) {
            inviter.sendMessage("§cВы не можете пригласить самого себя!")
            return true
        }

        val inviterIp = inviter.address?.address?.hostAddress
        val referralIp = referral.address?.address?.hostAddress

        if (inviterIp != null && referralIp != null && inviterIp == referralIp) {
            inviter.sendMessage("§cВы не можете пригласить игрока с таким же IP-адресом!")
            return true
        }

        val referralId = referral.uniqueId

        if (referralManager.isReferral(referralId)) {
            inviter.sendMessage("§cЭтот игрок уже является рефералом!")
            return true
        }

        // Record pending invitation
        referralManager.addPendingInvite(referralId, inviter.uniqueId)

        // Send invitation message to referral
        sendInvitationMessage(inviter, referral)

        inviter.sendMessage("§aПриглашение отправлено игроку $referralName")

        return true
    }

    private fun sendInvitationMessage(inviter: Player, referral: Player) {
        val message = Component.text("Вы являетесь рефералом игрока ")
            .color(NamedTextColor.YELLOW)
            .append(Component.text(inviter.name).color(NamedTextColor.GREEN))
            .append(Component.text(". Принять приглашение?\n").color(NamedTextColor.YELLOW))
            .append(Component.text("[ДА]").color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/referral_accept ${inviter.name} ${referral.name}")))
            .append(Component.text(" "))
            .append(Component.text("[НЕТ]").color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/referral_decline ${inviter.name} ${referral.name}")))

        referral.sendMessage(message)
    }
}