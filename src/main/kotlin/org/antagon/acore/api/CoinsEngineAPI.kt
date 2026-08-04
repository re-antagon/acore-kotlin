package org.antagon.acore.api


import org.bukkit.OfflinePlayer
import org.antagon.acore.api.Currency

object CoinsEngineAPI {
    fun getCurrency(id: String): Currency? = null
    fun getBalance(player: OfflinePlayer, currency: Currency): Double = 0.0
    fun addBalance(player: OfflinePlayer, currency: Currency, amount: Double) {}
    fun removeBalance(player: OfflinePlayer, currency: Currency, amount: Double) {}
}