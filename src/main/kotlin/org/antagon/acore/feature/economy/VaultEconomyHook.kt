package org.antagon.acore.feature.economy

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

// keep Vault types out of the listener
internal class VaultEconomyHook private constructor(private val economy: Economy) : TransferEconomy {
    override val name = "Vault (${economy.name})"
    override fun getBalance(player: Player) = economy.getBalance(player)
    override fun withdraw(player: Player, amount: Double) = economy.withdrawPlayer(player, amount).transactionSuccess()
    override fun deposit(player: Player, amount: Double) = economy.depositPlayer(player, amount).transactionSuccess()

    companion object {
        fun connect(): TransferEconomy? = Bukkit.getServicesManager().getRegistration(Economy::class.java)
            ?.provider?.let(::VaultEconomyHook)
    }
}