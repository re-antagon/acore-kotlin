package org.antagon.acore.listener

import org.antagon.acore.util.ReferralManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class ReferralListener(private val plugin: JavaPlugin, private val referralManager: ReferralManager) : Listener {

    init {
        // Start scheduled task to check referral time every minute
        startReferralTimeChecker()
    }

    private fun startReferralTimeChecker() {
        object : BukkitRunnable() {
            override fun run() {
                checkReferralTimes()
            }
        }.runTaskTimer(plugin, 1200L, 1200L) // Run every 60 seconds (1200 ticks)
    }

    private fun checkReferralTimes() {
        val currentTime = System.currentTimeMillis()
        val sevenHoursMs = 7 * 60 * 60 * 1000L

        // Check all active referrals
        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.uniqueId

            if (referralManager.isReferral(playerId) && !referralManager.isReferralRewarded(playerId)) {
                val startTime = referralManager.getReferralStartTime(playerId)
                if (startTime != null) {
                    val playedTime = currentTime - startTime

                    if (playedTime >= sevenHoursMs) {
                        // Give reward to inviter
                        val inviterId = referralManager.getInviter(playerId)
                        if (inviterId != null) {
                            val inviter = Bukkit.getPlayer(inviterId)
                            if (inviter != null) {
                                giveReward(inviter, 9)
                                inviter.sendMessage("§aВаш реферал ${player.name} отыграл 7 часов! Вы получили награду.")
                            }
                        }

                        // Mark as rewarded
                        referralManager.markReferralRewarded(playerId)
                        player.sendMessage("§aВы отыграли 7 часов как реферал! Ваш пригласивший получил награду.")
                        plugin.logger.info("Referral ${player.name} completed 7 hours playtime")
                    }
                }
            }
        }
    }

    private fun handleAccept(referral: Player, inviterName: String, referralName: String) {
        // Verify that the command is for this player
        if (referral.name != referralName) {
            return
        }

        val inviter = Bukkit.getPlayer(inviterName)
        if (inviter == null) {
            referral.sendMessage("§cПригласивший игрок не найден!")
            return
        }

        // Check if already a referral
        if (referralManager.isReferral(referral.uniqueId)) {
            referral.sendMessage("§cВы уже являетесь рефералом!")
            return
        }

        // Add referral
        referralManager.addReferral(referral.uniqueId, inviter.uniqueId)

        // Give initial reward to inviter
        giveReward(inviter, 1)

        // Start tracking time
        referralManager.startReferralTracking(referral.uniqueId)

        referral.sendMessage("§aВы приняли приглашение от $inviterName!")
        inviter.sendMessage("§aИгрок $referralName принял ваше приглашение!")

        plugin.logger.info("Player $referralName accepted referral from $inviterName")
    }

    private fun handleDecline(referral: Player, inviterName: String, referralName: String) {
        // Verify that the command is for this player
        if (referral.name != referralName) {
            return
        }

        referral.sendMessage("§cВы отклонили приглашение от $inviterName")

        val inviter = Bukkit.getPlayer(inviterName)
        if (inviter != null) {
            inviter.sendMessage("§cИгрок $referralName отклонил ваше приглашение")
        }

        plugin.logger.info("Player $referralName declined referral from $inviterName")
    }

    private fun giveReward(player: Player, amount: Int) {
        val command = "antacoin give ${player.name} $amount"
        plugin.server.dispatchCommand(plugin.server.consoleSender, command)
    }

    @EventHandler
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val message = event.message
        val player = event.player

        // Check if this is a referral command
        if (message.startsWith("/referral_accept ")) {
            event.isCancelled = true
            val parts = message.split(" ")
            if (parts.size == 3) {
                handleAccept(player, parts[1], parts[2])
            }
        } else if (message.startsWith("/referral_decline ")) {
            event.isCancelled = true
            val parts = message.split(" ")
            if (parts.size == 3) {
                handleDecline(player, parts[1], parts[2])
            }
        }
    }
}