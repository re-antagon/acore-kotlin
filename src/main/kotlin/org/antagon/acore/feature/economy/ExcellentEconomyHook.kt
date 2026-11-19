package org.antagon.acore.feature.economy

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI
import su.nightexpress.excellenteconomy.api.currency.operation.NotificationTarget
import su.nightexpress.excellenteconomy.api.currency.operation.OperationContext

// loaded only when ExcellentEconomy is enabled
internal class ExcellentEconomyHook private constructor(
    private val api: ExcellentEconomyAPI,
    private val currencyId: String,
    pluginName: String
) : TransferEconomy {
    override val name = "ExcellentEconomy ($currencyId)"
    private val context = OperationContext.custom(pluginName).silentFor(
        NotificationTarget.USER, NotificationTarget.EXECUTOR, NotificationTarget.CONSOLE_LOGGER
    )

    override fun getBalance(player: Player) = api.getBalance(player, currencyId)
    override fun normalizeAmount(amount: Double) = api.getCurrency(currencyId)?.floorIfNeeded(amount) ?: amount

    // in 2.8.0 the API can return true even when ChangeBalanceEvent was cancelled
    override fun withdraw(player: Player, amount: Double): Boolean {
        val balance = getBalance(player)
        val expected = balance - amount
        if (expected < 0.0 || expected == balance) return false
        return api.withdraw(player, currencyId, amount, context) && getBalance(player) == expected
    }

    override fun deposit(player: Player, amount: Double): Boolean {
        val balance = getBalance(player)
        val expected = balance + amount
        if (!expected.isFinite() || expected == balance) return false
        return api.deposit(player, currencyId, amount, context) && getBalance(player) == expected
    }

    override fun validateTransfer(sender: Player, target: Player, amount: Double, netAmount: Double): String? {
        if (!api.canPerformOperations()) return "Операции с валютой временно недоступны!"
        val currency = api.getCurrency(currencyId) ?: return "Валюта '$currencyId' не найдена в ExcellentEconomy!"
        if (!sender.hasPermission("excellenteconomy.command.currency.send") || !currency.hasPermission(sender)) {
            return "У вас нет разрешения на перевод этой валюты!"
        }
        if (!currency.isTransferAllowed) return "Переводы этой валюты отключены!"
        if (amount < currency.minTransferAmount) return "Минимальная сумма перевода: ${currency.minTransferAmount}!"
        if (!api.getCachedUserData(target).getSettings(currency).isPaymentsEnabled) {
            return "Получатель отключил входящие переводы этой валюты!"
        }
        if (!currency.isUnderLimit(getBalance(target) + netAmount)) return "Перевод превысит лимит баланса получателя!"
        return null
    }

    companion object {
        fun connect(currencyId: String, pluginName: String): TransferEconomy? {
            val api = Bukkit.getServicesManager().getRegistration(ExcellentEconomyAPI::class.java)?.provider
                ?: return null
            return ExcellentEconomyHook(api, currencyId, pluginName)
        }
    }
}