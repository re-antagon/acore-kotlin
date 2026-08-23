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
import org.antagon.acore.listener.ReferralManager
import org.antagon.acore.listener.StreakManager
import org.bukkit.entity.Player

import com.mojang.brigadier.arguments.IntegerArgumentType
import org.antagon.acore.listener.PhysicsGunModule
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
            val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()

            commands.register(
                Commands.literal("acore")
                    .then(
                        Commands.literal("reload")
                            .requires { source -> source.sender.hasPermission("acore.admin") }
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                try {
                                    plugin.reloadPlugin()
                                    plugin.localizationManager.send(sender, "reload")
                                } catch (e: Exception) {
                                    plugin.localizationManager.send(sender, "reload-fail", mapOf("error" to (e.message ?: "unknown")))
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
                                    plugin.localizationManager.send(sender, "only-players")
                                    return@executes 1
                                }
                                plugin.localizationManager.send(sender, "visualize-usage")
                                plugin.localizationManager.send(sender, "visualize-example")
                                plugin.localizationManager.send(sender, "visualize-clear-help")
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
                                            sender.sendMessage(mm.deserialize("<color:#fc5454>Эта команда доступна только игрокам!</color:#fc5454>"))
                                            return@executes 1
                                        }

                                        val argsStr = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "args")
                                        if (argsStr.equals("clear", ignoreCase = true)) {
                                            org.antagon.acore.listener.CoreProtectVisualizerListener.SessionManager.stopSession(sender)
                                            plugin.localizationManager.send(sender, "visualize-cleared")
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
                            .requires { source -> source.sender.hasPermission("acore.admin") }
                            .then(
                                Commands.literal("get")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .executes { ctx ->
                                                val sender = ctx.source.sender
                                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                val resolved = resolver.resolve(ctx.source)
                                                if (resolved.isEmpty()) {
                                                    sender.sendMessage(mm.deserialize("<color:#fc5454>Игрок не найден!</color:#fc5454>"))
                                                    return@executes 1
                                                }
                                                val target = resolved.first()
                                                val data = streakManager.getCachedData(target.uniqueId) ?: streakManager.loadOrInit(target.uniqueId)
                                                sender.sendMessage(mm.deserialize("<gold>=== Статистика стрика: <yellow>${target.name}</yellow> ===</gold>"))
                                                sender.sendMessage(mm.deserialize("<gray>Текущий стрик: <green>${data.currentStreak}</green> дн.</gray>"))
                                                sender.sendMessage(mm.deserialize("<gray>Рекордный стрик: <green>${data.highestStreak}</green> дн.</gray>"))
                                                sender.sendMessage(mm.deserialize("<gray>Всего дней входа: <green>${data.totalLogins}</green></gray>"))
                                                sender.sendMessage(mm.deserialize("<gray>Заморозок: <aqua>${data.streakFreezes}</aqua></gray>"))
                                                sender.sendMessage(mm.deserialize("<gray>Последний вход: <yellow>${data.lastLoginDate ?: "Никогда"}</yellow></gray>"))
                                                sender.sendMessage(mm.deserialize("<gray>До сброса суток: <yellow>${streakManager.getTimeUntilReset()}</yellow></gray>"))
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
                                                            sender.sendMessage(mm.deserialize("<color:#fc5454>Игрок не найден!</color:#fc5454>"))
                                                            return@executes 1
                                                        }
                                                        val target = resolved.first()
                                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                        streakManager.setStreak(target.uniqueId, amount)
                                                        sender.sendMessage(mm.deserialize("<green>Стрик игрока <yellow>${target.name}</yellow> успешно установлен на <gold>$amount</gold> дн.</green>"))
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
                                                            sender.sendMessage(mm.deserialize("<color:#fc5454>Игрок не найден!</color:#fc5454>"))
                                                            return@executes 1
                                                        }
                                                        val target = resolved.first()
                                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                                        val data = streakManager.addFreezes(target.uniqueId, amount)
                                                        sender.sendMessage(mm.deserialize("<green>Заморозки игрока <yellow>${target.name}</yellow> изменены на <gold>$amount</gold>. Новый баланс: <aqua>${data.streakFreezes}</aqua></green>"))
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
                                                    sender.sendMessage(mm.deserialize("<color:#fc5454>Игрок не найден!</color:#fc5454>"))
                                                    return@executes 1
                                                }
                                                val target = resolved.first()
                                                streakManager.resetStreak(target.uniqueId)
                                                sender.sendMessage(mm.deserialize("<green>Стрик игрока <yellow>${target.name}</yellow> сброшен.</green>"))
                                                1
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("pgun")
                            .requires { source -> source.sender.hasPermission("acore.physicsgun.use") }
                            .then(
                                Commands.literal("give")
                                    .then(
                                        Commands.argument("player", ArgumentTypes.player())
                                            .executes { ctx ->
                                                val sender = ctx.source.sender
                                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                                val target = resolver.resolve(ctx.source).firstOrNull()
                                                if (target == null) {
                                                    plugin.localizationManager.send(sender, "physicsgun-player-not-found")
                                                    return@executes 1
                                                }
                                                val module = PhysicsGunModule.current
                                                if (module == null) {
                                                    plugin.localizationManager.send(sender, "physicsgun-disabled")
                                                    return@executes 1
                                                }
                                                val leftover = target.inventory.addItem(module.gunItemService.createGun())
                                                leftover.values.forEach { target.world.dropItemNaturally(target.location, it) }
                                                plugin.localizationManager.send(sender, "physicsgun-give-success", mapOf("player" to target.name))
                                                1
                                            }
                                    )
                            )
                            .executes { ctx ->
                                plugin.localizationManager.send(ctx.source.sender, "physicsgun-usage")
                                1
                            }
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
                                sender.sendMessage(mm.deserialize("<color:#fc5454>Эта команда доступна только игрокам!</color:#fc5454>"))
                                return@executes 1
                            }

                            val player = sender
                            val hasPermission = player.hasPermission("bossbar.show")

                            val luckPerms = DependencyHandler.getLuckPerms()
                            if (luckPerms == null) {
                                player.sendMessage(mm.deserialize("<color:#fc5454>Ошибка: данные LuckPerms еще не загружены!</color:#fc5454>"))
                                return@executes 1
                            }

                            val user = luckPerms.userManager.getUser(player.uniqueId)
                            if (user == null) {
                                player.sendMessage(mm.deserialize("<color:#fc5454>Ошибка: данные LuckPerms еще не загружены!</color:#fc5454>"))
                                return@executes 1
                            }

                            if (hasPermission) {
                                val node = PermissionNode.builder("bossbar.show").value(false).build()
                                user.data().add(node)
                                luckPerms.userManager.saveUser(user)
                                player.sendMessage(mm.deserialize("<color:#fc5454>Bossbar отключен!</color:#fc5454>"))
                            } else {
                                val node = PermissionNode.builder("bossbar.show").value(true).build()
                                user.data().add(node)
                                luckPerms.userManager.saveUser(user)
                                player.sendMessage(mm.deserialize("<green>Bossbar включен!</green>"))
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
                                    sender.sendMessage(mm.deserialize("<color:#fc5454>Эта команда доступна только игрокам!</color:#fc5454>"))
                                    return@executes 1
                                }

                                val inviter = sender

                                // Check if feature is enabled
                                if (!configManager.getBoolean("referrals.enabled", true)) {
                                    inviter.sendMessage(mm.deserialize("<color:#fc5454>Эта фича временно отключена!</color:#fc5454>"))
                                    return@executes 1
                                }

                                val resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver::class.java)
                                val resolvedPlayers = resolver.resolve(ctx.source)
                                if (resolvedPlayers.isEmpty()) {
                                    inviter.sendMessage(mm.deserialize("<color:#fc5454>Игрок не найден!</color:#fc5454>"))
                                    return@executes 1
                                }
                                val referral = resolvedPlayers.first()

                                if (referral == inviter) {
                                    inviter.sendMessage(mm.deserialize("<color:#fc5454>Вы не можете пригласить самого себя!</color:#fc5454>"))
                                    return@executes 1
                                }

                                val inviterIp = inviter.address?.address?.hostAddress
                                val referralIp = referral.address?.address?.hostAddress

                                if (inviterIp != null && referralIp != null && inviterIp == referralIp) {
                                    inviter.sendMessage(mm.deserialize("<color:#fc5454>Вы не можете пригласить игрока с таким же IP-адресом!</color:#fc5454>"))
                                    return@executes 1
                                }

                                val referralId = referral.uniqueId

                                if (referralManager.isReferral(referralId)) {
                                    inviter.sendMessage(mm.deserialize("<color:#fc5454>Этот игрок уже является рефералом!</color:#fc5454>"))
                                    return@executes 1
                                }

                                // Record pending invitation
                                referralManager.addPendingInvite(referralId, inviter.uniqueId)

                                // Send invitation message to referral
                                sendInvitationMessage(inviter, referral)

                                inviter.sendMessage(mm.deserialize("<green>Приглашение отправлено игроку <yellow>${referral.name}</yellow></green>"))
                                1
                            }
                    )
                    .build(),
                "Invite a player to become your referral"
            )
        }
    }

    private fun sendInvitationMessage(inviter: Player, referral: Player) {
        val mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        val message = mm.deserialize(
            "<yellow>Вы являетесь рефералом игрока <green>${inviter.name}</green>. Принять приглашение?</yellow>\n" +
            "<green><bold><click:run_command:/referral_accept ${inviter.name} ${referral.name}>[ДА]</click></bold></green> " +
            "<color:#fc5454><bold><click:run_command:/referral_decline ${inviter.name} ${referral.name}>[НЕТ]</click></bold></color:#fc5454>"
        )

        referral.sendMessage(message)
    }
}
