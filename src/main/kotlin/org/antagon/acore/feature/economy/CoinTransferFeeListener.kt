package org.antagon.acore.feature.economy

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.util.DependencyHandler
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.Plugin

class CoinTransferFeeListener(
    private val plugin: Plugin = Acore.instance,
    private val config: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Coin Transfer Fee"

    override fun shouldEnable(): Boolean {
        return config.getBoolean("coinTransferFee.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private fun getEconomy(currencyId: String): TransferEconomy? {
        return try {
            when {
                DependencyHandler.isPluginEnabled("ExcellentEconomy") ->
                    ExcellentEconomyHook.connect(currencyId, plugin.name)
                DependencyHandler.isPluginEnabled("Vault") -> VaultEconomyHook.connect()
                else -> null
            }
        } catch (e: LinkageError) {
            plugin.logger.warning("CoinTransferFee: Economy API is not accessible: ${e.message}")
            null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (!config.getBoolean("coinTransferFee.enabled", true)) return

        val message = event.message.trim()
        if (!message.startsWith("/")) return

        val rawCmd = message.substring(1)
        val parts = rawCmd.split("\\s+".toRegex())
        if (parts.isEmpty()) return

        val commandParts = parts.map { it.lowercase() }.toMutableList()
        commandParts[0] = commandParts[0].substringAfter(':')
        val command = config.getStringList("coinTransferFee.commands")
            .map { it.trim().removePrefix("/").lowercase().split("\\s+".toRegex()) }
            .filter { it.isNotEmpty() && it[0].isNotEmpty() }
            .sortedByDescending { it.size }
            .firstOrNull { prefix ->
                parts.size == prefix.size + 2 && commandParts.take(prefix.size) == prefix
            } ?: return
        val targetIndex = command.size
        val amountIndex = targetIndex + 1

        val sender = event.player
        event.isCancelled = true
        val targetName = parts[targetIndex]
        val amountStr = parts[amountIndex]

        val requestedAmount = amountStr.toDoubleOrNull()
        if (requestedAmount == null || !requestedAmount.isFinite() || requestedAmount <= 0.0) {
            sender.sendMessage(Component.text("Укажите положительную сумму числом без сокращений!", NamedTextColor.RED))
            return
        }

        val targetPlayer = Bukkit.getPlayerExact(targetName)
        if (targetPlayer == null || !targetPlayer.isOnline) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Игрок '$targetName' не найден или не в сети!</color:#fc5454>"))
            return
        }

        if (targetPlayer.uniqueId == sender.uniqueId) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Вы не можете перевести АнтаКойны самому себе!</color:#fc5454>"))
            return
        }

        val currencyId = config.getString("coinTransferFee.currency", "antacoin")
        val mm = MiniMessage.miniMessage()
        val economy = getEconomy(currencyId)
        if (economy == null) {
            sender.sendMessage(mm.deserialize("<red>Ошибка экономики: провайдер ExcellentEconomy или Vault недоступен!</red>"))
            plugin.logger.warning("CoinTransferFee: No economy provider available for '$currencyId'.")
            return
        }
        val amount = economy.normalizeAmount(requestedAmount)
        val feePercentage = config.getDouble("coinTransferFee.fee-percentage", 5.0)
        val fixedFee = config.getDouble("coinTransferFee.fixed-fee", 0.0)
        val minFee = config.getDouble("coinTransferFee.min-fee", 1.0)
        val maxFee = config.getDouble("coinTransferFee.max-fee", 0.0)

        if (listOf(feePercentage, fixedFee, minFee, maxFee).any { !it.isFinite() || it < 0.0 }) {
            plugin.logger.warning("CoinTransferFee: Fee settings must be finite and non-negative.")
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>Ошибка настройки комиссии!</red>"))
            return
        }

        // calculating fee
        val rawFee = amount * (feePercentage / 100.0) + fixedFee
        var fee = rawFee.coerceAtLeast(minFee)
        if (maxFee > 0.0) {
            fee = fee.coerceAtMost(maxFee)
        }

        // the amount that will actually reach the recipient: amount - fee
        val netAmount = economy.normalizeAmount(amount - fee)
        fee = amount - netAmount
        if (!fee.isFinite() || !netAmount.isFinite() || netAmount <= 0.0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Сумма перевода слишком мала для покрытия комиссии (комиссия: <color:#fceba0>$fee</color:#fceba0>)!</color:#fc5454>"))
            return
        }

        val validationError = economy.validateTransfer(sender, targetPlayer, amount, netAmount)
        if (validationError != null) {
            sender.sendMessage(Component.text(validationError, NamedTextColor.RED))
            return
        }
        val senderBalance = economy.getBalance(sender)
        if (senderBalance < amount) {
            sender.sendMessage(mm.deserialize("<color:#fc5454>Недостаточно средств! Нужно: <color:#fceba0>$amount</color:#fceba0> <color:#d1d6d5>(у вас: <color:#fceba0>$senderBalance</color:#fceba0>)</color:#d1d6d5></color:#fc5454>"))
            return
        }

        val result = performTransfer(
            withdraw = { economy.withdraw(sender, amount) },
            deposit = { economy.deposit(targetPlayer, netAmount) },
            refund = { economy.deposit(sender, amount) }
        )
        if (result != TransferResult.SUCCESS) {
            val error = when (result) {
                TransferResult.WITHDRAWAL_FAILED -> "Не удалось списать средства. Перевод отменён!"
                TransferResult.DEPOSIT_FAILED -> "Не удалось зачислить перевод. Средства возвращены!"
                else -> "Не удалось зачислить перевод и вернуть средства. Обратитесь к администрации!"
            }
            sender.sendMessage(mm.deserialize("<red>$error</red>"))
            val details = "CoinTransferFee: $result via ${economy.name}, sender=${sender.uniqueId}, " +
                "target=${targetPlayer.uniqueId}, amount=$amount, net=$netAmount"
            if (result == TransferResult.REFUND_FAILED) plugin.logger.severe(details)
            else plugin.logger.warning(details)
            return
        }

        sender.sendMessage(mm.deserialize("<color:#d1d6d5>Вы перевели <color:#fceba0>$amount</color:#fceba0> АнтаКойнов игроку <color:#fceba0>${targetPlayer.name}</color:#fceba0>. Комиссия: <color:#fceba0>$fee</color:#fceba0> АнтаКойнов. Получатель получит: <color:#fceba0>$netAmount</color:#fceba0> АнтаКойнов.</color:#d1d6d5>"))
        targetPlayer.sendMessage(mm.deserialize("<color:#d1d6d5>Вам поступил перевод <color:#fceba0>$netAmount</color:#fceba0> АнтаКойнов от игрока <color:#fceba0>${sender.name}</color:#fceba0> <color:#d1d6d5>(с учетом комиссии <color:#fceba0>$fee</color:#fceba0>)</color:#d1d6d5>.</color:#d1d6d5>"))
        plugin.logger.info("Player ${sender.name} transferred $amount to ${targetPlayer.name} via ${economy.name}. Fee: $fee, Net: $netAmount")
    }
}