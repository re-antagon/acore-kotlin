package org.antagon.acore.listener

import net.kyori.adventure.text.minimessage.MiniMessage
import net.milkbowl.vault.economy.Economy
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

    private fun getVaultEconomy(): Economy? {
        if (!DependencyHandler.isPluginEnabled("Vault")) return null
        return try {
            val rsp = Bukkit.getServer().servicesManager.getRegistration(Economy::class.java)
            rsp?.provider
        } catch (e: Throwable) {
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

        val commandLower = parts[0].lowercase()
        
        var isTransferCmd = false
        var targetIndex = -1
        var amountIndex = -1

        if (commandLower in listOf("pay", "send")) {
            if (parts.size >= 3) {
                isTransferCmd = true
                targetIndex = 1
                amountIndex = 2
            }
        } else if (commandLower in listOf("antacoin", "coinsengine", "ce", "coe")) {
            if (parts.size >= 3) {
                val sub = parts[1].lowercase()
                if (sub in listOf("pay", "send")) {
                    isTransferCmd = true
                    targetIndex = 2
                    amountIndex = 3
                }
            }
        }

        if (!isTransferCmd || targetIndex == -1 || amountIndex == -1 || parts.size <= amountIndex) {
            return
        }

        val sender = event.player
        val targetName = parts[targetIndex]
        val amountStr = parts[amountIndex]

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            return
        }

        val targetPlayer = Bukkit.getPlayer(targetName)
        if (targetPlayer == null || !targetPlayer.isOnline) {
            event.isCancelled = true
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Игрок '$targetName' не найден или не в сети!</color:#fc5454>"))
            return
        }

        if (targetPlayer.uniqueId == sender.uniqueId) {
            event.isCancelled = true
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Вы не можете перевести АнтаКойны самому себе!</color:#fc5454>"))
            return
        }

        val currencyId = config.getString("coinTransferFee.currency", "antacoin")
        val feePercentage = config.getDouble("coinTransferFee.fee-percentage", 5.0)
        val fixedFee = config.getDouble("coinTransferFee.fixed-fee", 0.0)
        val minFee = config.getDouble("coinTransferFee.min-fee", 1.0)
        val maxFee = config.getDouble("coinTransferFee.max-fee", 0.0)

        // calculating fee
        val rawFee = amount * (feePercentage / 100.0) + fixedFee
        var fee = rawFee.coerceAtLeast(minFee)
        if (maxFee > 0.0) {
            fee = fee.coerceAtMost(maxFee)
        }

        // the amount that will actually reach the recipient: amount - fee
        val netAmount = amount - fee
        if (netAmount <= 0.0) {
            event.isCancelled = true
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<color:#fc5454>Сумма перевода слишком мала для покрытия комиссии (комиссия: <color:#fceba0>$fee</color:#fceba0>)!</color:#fc5454>"))
            return
        }

        val mm = MiniMessage.miniMessage()

        // Vault Economy processing
        val vaultEco = getVaultEconomy()
        if (vaultEco != null) {
            val senderBalance = vaultEco.getBalance(sender)
            if (senderBalance < amount) {
                event.isCancelled = true
                sender.sendMessage(mm.deserialize("<color:#fc5454>Недостаточно средств! Нужно: <color:#fceba0>$amount</color:#fceba0> <color:#d1d6d5>(у вас: <color:#fceba0>$senderBalance</color:#fceba0>)</color:#d1d6d5></color:#fc5454>"))
                return
            }

            event.isCancelled = true
            vaultEco.withdrawPlayer(sender, amount)
            vaultEco.depositPlayer(targetPlayer, netAmount)

            sender.sendMessage(mm.deserialize("<color:#d1d6d5>Вы перевели <color:#fceba0>$amount</color:#fceba0> АнтаКойнов игроку <color:#fceba0>${targetPlayer.name}</color:#fceba0>. Комиссия: <color:#fceba0>$fee</color:#fceba0> АнтаКойнов. Получатель получит: <color:#fceba0>$netAmount</color:#fceba0> АнтаКойнов.</color:#d1d6d5>"))
            targetPlayer.sendMessage(mm.deserialize("<color:#d1d6d5>Вам поступил перевод <color:#fceba0>$netAmount</color:#fceba0> АнтаКойнов от игрока <color:#fceba0>${sender.name}</color:#fceba0> <color:#d1d6d5>(с учетом комиссии <color:#fceba0>$fee</color:#fceba0>)</color:#d1d6d5>.</color:#d1d6d5>"))
            plugin.logger.info("Player ${sender.name} transferred $amount to ${targetPlayer.name} via Vault. Fee: $fee, Net: $netAmount")
            return
        }

        // If no economy provider found
        event.isCancelled = true
        sender.sendMessage(mm.deserialize("<color:#fc5454>Ошибка экономики: Vault провайдер не найден для валюты '$currencyId'!</color:#fc5454>"))
        plugin.logger.warning("CoinTransferFee: Failed to resolve economy provider via Vault.")
    }
}