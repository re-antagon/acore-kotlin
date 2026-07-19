package org.antagon.acore.commands

import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.types.PermissionNode
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.util.ReferralManager
import org.bukkit.entity.Player

class AcoreCommand(
    private val plugin: Acore,
    private val configManager: ConfigManager,
    private val referralManager: ReferralManager
) {

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()

            // 1. Register /acore reload
            commands.register(
                Commands.literal("acore")
                    .then(
                        Commands.literal("reload")
                            .requires { source -> source.sender.hasPermission("acore.admin") }
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                try {
                                    plugin.reloadPlugin()
                                    sender.sendMessage(Component.text("§aAcore plugin reloaded successfully!"))
                                } catch (e: Exception) {
                                    sender.sendMessage(Component.text("§cFailed to reload Acore: ${e.message}"))
                                }
                                1 // Command.SINGLE_SUCCESS
                            }
                    )
                    .build(),
                "Acore main command",
                listOf("ac")
            )

            // 2. Register /showinfo
            commands.register(
                Commands.literal("showinfo")
                    .requires { source -> source.sender.hasPermission("acore.showinfo") }
                    .executes { ctx ->
                        val sender = ctx.source.sender
                        if (sender !is Player) {
                            sender.sendMessage("§cЭта команда доступна только игрокам!")
                            return@executes 1
                        }

                        val player = sender
                        val hasPermission = player.hasPermission("bossbar.show")

                        val luckPerms = LuckPermsProvider.get()
                        val user = luckPerms.userManager.getUser(player.uniqueId)
                        if (user == null) {
                            player.sendMessage("§cОшибка: данные LuckPerms еще не загружены!")
                            return@executes 1
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
                        1
                    }
                    .build(),
                "Toggleable bossbar"
            )

            // 3. Register /link <player>
            commands.register(
                Commands.literal("link")
                    .requires { source -> source.sender.hasPermission("acore.link") }
                    .then(
                        Commands.argument("player", ArgumentTypes.player())
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                if (sender !is Player) {
                                    sender.sendMessage("§cЭта команда доступна только игрокам!")
                                    return@executes 1
                                }

                                val inviter = sender

                                // Check if feature is enabled
                                if (!configManager.getBoolean("referrals.enabled", true)) {
                                    inviter.sendMessage("§cЭта фича временно отключена!")
                                    return@executes 1
                                }

                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                val resolvedPlayers = resolver.resolve(ctx.source)
                                if (resolvedPlayers.isEmpty()) {
                                    inviter.sendMessage("§cИгрок не найден!")
                                    return@executes 1
                                }
                                val referral = resolvedPlayers.first()

                                if (referral == inviter) {
                                    inviter.sendMessage("§cВы не можете пригласить самого себя!")
                                    return@executes 1
                                }

                                val inviterIp = inviter.address?.address?.hostAddress
                                val referralIp = referral.address?.address?.hostAddress

                                if (inviterIp != null && referralIp != null && inviterIp == referralIp) {
                                    inviter.sendMessage("§cВы не можете пригласить игрока с таким же IP-адресом!")
                                    return@executes 1
                                }

                                val referralId = referral.uniqueId

                                if (referralManager.isReferral(referralId)) {
                                    inviter.sendMessage("§cЭтот игрок уже является рефералом!")
                                    return@executes 1
                                }

                                // Record pending invitation
                                referralManager.addPendingInvite(referralId, inviter.uniqueId)

                                // Send invitation message to referral
                                sendInvitationMessage(inviter, referral)

                                inviter.sendMessage("§aПриглашение отправлено игроку ${referral.name}")
                                1
                            }
                    )
                    .build(),
                "Invite a player to become your referral"
            )
        }
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
