package org.antagon.acore.feature.economy

import org.bukkit.entity.Player

internal interface TransferEconomy {
    val name: String
    fun normalizeAmount(amount: Double): Double = amount
    fun getBalance(player: Player): Double
    fun withdraw(player: Player, amount: Double): Boolean
    fun deposit(player: Player, amount: Double): Boolean
    fun validateTransfer(sender: Player, target: Player, amount: Double, netAmount: Double): String? = null
}

internal enum class TransferResult {
    SUCCESS,
    WITHDRAWAL_FAILED,
    DEPOSIT_FAILED,
    REFUND_FAILED
}

// a failed recipient deposit must refund the full withdrawal
internal fun performTransfer(
    withdraw: () -> Boolean,
    deposit: () -> Boolean,
    refund: () -> Boolean
): TransferResult {
    if (!withdraw()) return TransferResult.WITHDRAWAL_FAILED
    if (deposit()) return TransferResult.SUCCESS
    return if (refund()) TransferResult.DEPOSIT_FAILED else TransferResult.REFUND_FAILED
}