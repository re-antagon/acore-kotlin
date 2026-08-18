package org.antagon.acore.listener

import net.kyori.adventure.text.minimessage.MiniMessage
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.referral.ReferralManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask

class ReferralListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val referralManager: ReferralManager = Acore.instance.referralManager,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Referrals"

    private var task: BukkitTask? = null
    private val mm = MiniMessage.miniMessage()

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("referrals.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
        startReferralTimeChecker()
    }

    override fun disable() {
        super.disable()
        task?.cancel()
        task = null
    }

    private fun startReferralTimeChecker() {
        task = object : BukkitRunnable() {
            override fun run() {
                checkReferralTimes()
            }
        }.runTaskTimer(plugin, 1200L, 1200L) // Run every 60 seconds (1200 ticks)
    }

    private fun checkReferralTimes() {
        val sevenHoursTicks = 7 * 60 * 60 * 20L // 7 hours in ticks (20 ticks/sec)

        // Check all active referrals
        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.uniqueId

            if (referralManager.isReferral(playerId) && !referralManager.isReferralRewarded(playerId)) {
                val startTime = referralManager.getReferralStartTime(playerId)
                if (startTime != null) {
                    val currentPlaytime = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE).toLong()
                    
                    // Fallback for old epoch timestamps in referrals.yml
                    val isOldTimestamp = startTime > 1_000_000_000_000L
                    val playedTime = if (isOldTimestamp) {
                        System.currentTimeMillis() - startTime
                    } else {
                        currentPlaytime - startTime
                    }
                    
                    val requiredTime = if (isOldTimestamp) {
                        7 * 60 * 60 * 1000L // 7 hours in milliseconds
                    } else {
                        sevenHoursTicks // 7 hours in ticks
                    }

                    if (playedTime >= requiredTime) {
                        // Give reward to inviter
                        val inviterId = referralManager.getInviter(playerId)
                        if (inviterId != null) {
                            val inviter = Bukkit.getPlayer(inviterId)
                            if (inviter != null) {
                                giveReward(inviter, 9)
                                inviter.sendMessage(mm.deserialize("<green>Ваш реферал <yellow>${player.name}</yellow> отыграл 7 часов! Вы получили награду.</green>"))
                            }
                        }

                        // Mark as rewarded
                        referralManager.markReferralRewarded(playerId)
                        player.sendMessage(mm.deserialize("<green>Вы отыграли 7 часов как реферал! Ваш пригласивший получил награду.</green>"))
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
            referral.sendMessage(mm.deserialize("<color:#fc5454>Пригласивший игрок не найден!</color:#fc5454>"))
            return
        }

        // Verify that there is a pending invite from this inviter
        val pendingInviterId = referralManager.getPendingInviter(referral.uniqueId)
        if (pendingInviterId != inviter.uniqueId) {
            referral.sendMessage(mm.deserialize("<color:#fc5454>У вас нет активного приглашения от этого игрока!</color:#fc5454>"))
            return
        }

        val referralIp = referral.address?.address?.hostAddress
        val inviterIp = inviter.address?.address?.hostAddress

        if (referralIp != null && inviterIp != null && referralIp == inviterIp) {
            referral.sendMessage(mm.deserialize("<color:#fc5454>Вы не можете принять приглашение от игрока с таким же IP-адресом!</color:#fc5454>"))
            inviter.sendMessage(mm.deserialize("<color:#fc5454>Игрок $referralName имеет такой же IP-адрес, как и вы!</color:#fc5454>"))
            referralManager.removePendingInvite(referral.uniqueId)
            return
        }

        // Check if already a referral
        if (referralManager.isReferral(referral.uniqueId)) {
            referral.sendMessage(mm.deserialize("<color:#fc5454>Вы уже являетесь рефералом!</color:#fc5454>"))
            referralManager.removePendingInvite(referral.uniqueId)
            return
        }

        // Add referral and remove invite
        referralManager.addReferral(referral.uniqueId, inviter.uniqueId)
        referralManager.removePendingInvite(referral.uniqueId)

        // Give initial reward to inviter
        giveReward(inviter, 1)

        // Start tracking time using current playtime statistic (ticks)
        val currentPlaytime = referral.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE).toLong()
        referralManager.startReferralTracking(referral.uniqueId, currentPlaytime)

        referral.sendMessage(mm.deserialize("<green>Вы приняли приглашение от <yellow>$inviterName</yellow>!</green>"))
        inviter.sendMessage(mm.deserialize("<green>Игрок <yellow>$referralName</yellow> принял ваше приглашение!</green>"))

        plugin.logger.info("Player $referralName accepted referral from $inviterName")
    }

    private fun handleDecline(referral: Player, inviterName: String, referralName: String) {
        // Verify that the command is for this player
        if (referral.name != referralName) {
            return
        }

        val inviter = Bukkit.getPlayer(inviterName)
        if (inviter != null) {
            val pendingInviterId = referralManager.getPendingInviter(referral.uniqueId)
            if (pendingInviterId == inviter.uniqueId) {
                referralManager.removePendingInvite(referral.uniqueId)
            }
        }

        referral.sendMessage(mm.deserialize("<color:#fc5454>Вы отклонили приглашение от $inviterName</color:#fc5454>"))

        if (inviter != null) {
            inviter.sendMessage(mm.deserialize("<color:#fc5454>Игрок $referralName отклонил ваше приглашение</color:#fc5454>"))
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