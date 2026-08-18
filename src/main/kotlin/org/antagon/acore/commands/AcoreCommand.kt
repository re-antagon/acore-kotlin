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

import com.mojang.brigadier.arguments.IntegerArgumentType
import org.antagon.acore.streak.StreakManager
import org.antagon.acore.util.DependencyHandler

class AcoreCommand(
    private val plugin: Acore,
    private val configManager: ConfigManager,
    private val referralManager: ReferralManager,
    private val streakManager: StreakManager
) {

    companion object {
        private val cachedBlockMaterials: List<String> by lazy {
            org.bukkit.Material.entries.filter { it.isBlock && !it.isAir }.map { it.name.lowercase() }
        }
    }

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()

            // 1. Register /acore reload and /acore visualize
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
                                1
                            }
                    )
                    .then(
                        Commands.literal("visualize")
                            .requires { source -> source.sender.hasPermission("acore.visualize") }
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                if (sender !is Player) {
                                    sender.sendMessage("§cЭта команда доступна только игрокам!")
                                    return@executes 1
                                }
                                sender.sendMessage("§eИспользование: /acore visualize <CoreProtect lookup аргументы>")
                                sender.sendMessage("§7Пример: /acore visualize action:-block include:ancient_debris time:1h duration:30s")
                                sender.sendMessage("§7Для очистки: /acore visualize clear")
                                1
                            }
                            .then(
                                Commands.argument("args", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .suggests { ctx, builder ->
                                        val fullInput = builder.remaining
                                        val lastSpace = fullInput.lastIndexOf(' ')
                                        val prefix = if (lastSpace == -1) "" else fullInput.substring(0, lastSpace + 1)
                                        val token = if (lastSpace == -1) fullInput.lowercase() else fullInput.substring(lastSpace + 1).lowercase()

                                        when {
                                            token.startsWith("a:") || token.startsWith("action:") -> {
                                                val valPart = token.substringAfter(":")
                                                listOf("action:-block", "action:+block", "action:block", "action:click", "action:container", "action:kill", "action:item")
                                                    .filter { it.substringAfter(":").startsWith(valPart) }
                                                    .forEach { builder.suggest(prefix + it) }
                                            }
                                            token.startsWith("i:") || token.startsWith("include:") -> {
                                                val valPart = token.substringAfter(":")
                                                cachedBlockMaterials.filter { it.startsWith(valPart) }.take(15)
                                                    .forEach { builder.suggest(prefix + "include:" + it) }
                                            }
                                            token.startsWith("e:") || token.startsWith("exclude:") -> {
                                                val valPart = token.substringAfter(":")
                                                cachedBlockMaterials.filter { it.startsWith(valPart) }.take(15)
                                                    .forEach { builder.suggest(prefix + "exclude:" + it) }
                                            }
                                            token.startsWith("u:") || token.startsWith("user:") -> {
                                                val valPart = token.substringAfter(":")
                                                org.bukkit.Bukkit.getOnlinePlayers().map { it.name }
                                                    .filter { it.lowercase().startsWith(valPart) }
                                                    .forEach { builder.suggest(prefix + "user:" + it) }
                                            }
                                            token.startsWith("t:") || token.startsWith("time:") -> {
                                                listOf("time:10m", "time:1h", "time:12h", "time:1d")
                                                    .forEach { builder.suggest(prefix + it) }
                                            }
                                            token.startsWith("d:") || token.startsWith("duration:") -> {
                                                listOf("duration:15s", "duration:30s", "duration:1m", "duration:5m")
                                                    .forEach { builder.suggest(prefix + it) }
                                            }
                                            token.startsWith("r:") || token.startsWith("radius:") -> {
                                                listOf("radius:5", "radius:10", "radius:20", "radius:#world", "radius:#global")
                                                    .forEach { builder.suggest(prefix + it) }
                                            }
                                            else -> {
                                                listOf("action:", "include:", "exclude:", "user:", "time:", "duration:", "radius:", "clear")
                                                    .filter { it.startsWith(token) }
                                                    .forEach { builder.suggest(prefix + it) }
                                            }
                                        }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val sender = ctx.source.sender
                                        if (sender !is Player) {
                                            sender.sendMessage("§cЭта команда доступна только игрокам!")
                                            return@executes 1
                                        }

                                        val argsStr = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "args")
                                        if (argsStr.equals("clear", ignoreCase = true)) {
                                            org.antagon.acore.listener.CoreProtectVisualizerListener.SessionManager.stopSession(sender)
                                            sender.sendMessage("§aФантомная визуализация очищена.")
                                            return@executes 1
                                        }

                                        val query = org.antagon.acore.listener.CoreProtectVisualizerListener.Parser.parseArgs(argsStr, configManager)
                                        org.antagon.acore.listener.CoreProtectVisualizerListener.SessionManager.startSession(sender, query, configManager)
                                        1
                                    }
                            )
                    )
                    .then(
                        Commands.literal("streak")
                            .requires { source -> source.sender.hasPermission("acore.streak.admin") }
                            .then(
                                Commands.literal("get")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .executes { ctx ->
                                                val sender = ctx.source.sender
                                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                val resolved = resolver.resolve(ctx.source)
                                                if (resolved.isEmpty()) {
                                                    sender.sendMessage("§cИгрок не найден!")
                                                    return@executes 1
                                                }
                                                val target = resolved.first()
                                                val data = streakManager.getCachedData(target.uniqueId) ?: streakManager.loadOrInit(target.uniqueId)
                                                sender.sendMessage("§6=== Статистика стрика: §e${target.name} §6===")
                                                sender.sendMessage("§7Текущий стрик: §a${data.currentStreak} дн.")
                                                sender.sendMessage("§7Рекордный стрик: §a${data.highestStreak} дн.")
                                                sender.sendMessage("§7Всего дней входа: §a${data.totalLogins}")
                                                sender.sendMessage("§7Заморозок: §b${data.streakFreezes}")
                                                sender.sendMessage("§7Последний вход: §e${data.lastLoginDate ?: "Никогда"}")
                                                sender.sendMessage("§7До сброса суток: §e${streakManager.getTimeUntilReset()}")
                                                1
                                            }
                                    )
                            )
                            .then(
                                Commands.literal("set")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .then(
                                                Commands.argument("amount", IntegerArgumentType.integer(0))
                                                    .executes { ctx ->
                                                        val sender = ctx.source.sender
                                                        val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                        val resolved = resolver.resolve(ctx.source)
                                                        if (resolved.isEmpty()) {
                                                            sender.sendMessage("§cИгрок не найден!")
                                                            return@executes 1
                                                        }
                                                        val target = resolved.first()
                                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                        streakManager.setStreak(target.uniqueId, amount)
                                                        sender.sendMessage("§aСтрик игрока ${target.name} успешно установлен на $amount дн.")
                                                        1
                                                    }
                                            )
                                    )
                            )
                            .then(
                                Commands.literal("addfreeze")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .then(
                                                Commands.argument("amount", IntegerArgumentType.integer())
                                                    .executes { ctx ->
                                                        val sender = ctx.source.sender
                                                        val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                        val resolved = resolver.resolve(ctx.source)
                                                        if (resolved.isEmpty()) {
                                                            sender.sendMessage("§cИгрок не найден!")
                                                            return@executes 1
                                                        }
                                                        val target = resolved.first()
                                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                        val data = streakManager.addFreezes(target.uniqueId, amount)
                                                        sender.sendMessage("§aЗаморозки игрока ${target.name} изменены на $amount. Новый баланс: ${data.streakFreezes}")
                                                        1
                                                    }
                                            )
                                    )
                            )
                            .then(
                                Commands.literal("reset")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .executes { ctx ->
                                                val sender = ctx.source.sender
                                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                val resolved = resolver.resolve(ctx.source)
                                                if (resolved.isEmpty()) {
                                                    sender.sendMessage("§cИгрок не найден!")
                                                    return@executes 1
                                                }
                                                val target = resolved.first()
                                                streakManager.resetStreak(target.uniqueId)
                                                sender.sendMessage("§aСтрик игрока ${target.name} сброшен.")
                                                1
                                            }
                                    )
                            )
                    )
                    .build(),
                "Acore main command",
                listOf("ac")
            )

            // 2. Register /showinfo (only if LuckPerms is available)
            if (DependencyHandler.isPluginEnabled("LuckPerms")) {
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

                            val luckPerms = DependencyHandler.getLuckPerms()
                            if (luckPerms == null) {
                                player.sendMessage("§cОшибка: данные LuckPerms еще не загружены!")
                                return@executes 1
                            }

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
            } else {
                plugin.logger.warning("LuckPerms is missing or disabled. Command '/showinfo' will not be registered.")
            }

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
