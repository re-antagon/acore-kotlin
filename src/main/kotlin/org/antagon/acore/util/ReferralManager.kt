package org.antagon.acore.util

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.HashMap

class ReferralManager(private val plugin: JavaPlugin) {
    private val referralFile: File
    private val referrals: MutableMap<UUID, UUID> = HashMap() // реферал -> пригласивший
    private val inviterReferrals: MutableMap<UUID, MutableList<UUID>> = HashMap() // пригласивший -> список рефералов
    private val referralStartTime: MutableMap<UUID, Long> = HashMap() // реферал -> время начала трекинга (в миллисекундах)
    private val referralRewarded: MutableMap<UUID, Boolean> = HashMap() // реферал -> получил ли награду за 7 часов
    private lateinit var referralConfig: YamlConfiguration

    init {
        referralFile = File(plugin.dataFolder, "referrals.yml")
        loadReferrals()
    }

    // Load referrals from file
    private fun loadReferrals() {
        if (!referralFile.exists()) {
            try {
                referralFile.createNewFile()
                referralConfig = YamlConfiguration.loadConfiguration(referralFile)
                referralConfig.set("referrals", HashMap<String, String>())
                referralConfig.set("inviter-referrals", HashMap<String, List<String>>())
                referralConfig.set("referral-start-times", HashMap<String, Long>())
                referralConfig.set("referral-rewarded", HashMap<String, Boolean>())
                referralConfig.save(referralFile)
            } catch (e: IOException) {
                plugin.logger.severe("Failed to create referrals file: " + e.message)
                return
            }
        }

        referralConfig = YamlConfiguration.loadConfiguration(referralFile)

        // Load referrals map
        val referralsMap = referralConfig.getConfigurationSection("referrals")?.getValues(false) ?: return
        for ((key, value) in referralsMap) {
            try {
                val referralId = UUID.fromString(key)
                val inviterId = UUID.fromString(value as String)
                referrals[referralId] = inviterId
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid UUID in referrals file: $key -> $value")
            }
        }

        // Load inviter referrals map
        val inviterMap = referralConfig.getConfigurationSection("inviter-referrals")?.getValues(false) ?: return
        for ((key, value) in inviterMap) {
            try {
                val inviterId = UUID.fromString(key)
                val referralIds = referralConfig.getStringList("inviter-referrals.$key")
                val referralUUIDs = mutableListOf<UUID>()
                for (id in referralIds) {
                    referralUUIDs.add(UUID.fromString(id))
                }
                inviterReferrals[inviterId] = referralUUIDs
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid UUID in inviter referrals file: $key")
            }
        }

        // Load start times
        val startTimesMap = referralConfig.getConfigurationSection("referral-start-times")?.getValues(false) ?: return
        for ((key, value) in startTimesMap) {
            try {
                val referralId = UUID.fromString(key)
                val startTime = value as Long
                referralStartTime[referralId] = startTime
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid UUID in referral start times file: $key")
            }
        }

        // Load rewarded status
        val rewardedMap = referralConfig.getConfigurationSection("referral-rewarded")?.getValues(false) ?: return
        for ((key, value) in rewardedMap) {
            try {
                val referralId = UUID.fromString(key)
                val rewarded = value as Boolean
                referralRewarded[referralId] = rewarded
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid UUID in referral rewarded file: $key")
            }
        }

        plugin.logger.info("Loaded " + referrals.size + " referrals")
    }

    // Save referrals to file
    private fun saveReferrals() {
        // Save referrals map
        val referralsMap = HashMap<String, String>()
        for ((key, value) in referrals) {
            referralsMap[key.toString()] = value.toString()
        }
        referralConfig.set("referrals", referralsMap)

        // Save inviter referrals map
        val inviterMap = HashMap<String, List<String>>()
        for ((key, value) in inviterReferrals) {
            val referralIds = mutableListOf<String>()
            for (id in value) {
                referralIds.add(id.toString())
            }
            inviterMap[key.toString()] = referralIds
        }
        referralConfig.set("inviter-referrals", inviterMap)

        // Save start times
        val startTimesMap = HashMap<String, Long>()
        for ((key, value) in referralStartTime) {
            startTimesMap[key.toString()] = value
        }
        referralConfig.set("referral-start-times", startTimesMap)

        // Save rewarded status
        val rewardedMap = HashMap<String, Boolean>()
        for ((key, value) in referralRewarded) {
            rewardedMap[key.toString()] = value
        }
        referralConfig.set("referral-rewarded", rewardedMap)

        try {
            referralConfig.save(referralFile)
        } catch (e: IOException) {
            plugin.logger.severe("Failed to save referrals file: " + e.message)
        }
    }

    // Add referral
    fun addReferral(referralId: UUID, inviterId: UUID) {
        referrals[referralId] = inviterId

        // Add to inviter's list
        inviterReferrals.getOrPut(inviterId) { mutableListOf() }.add(referralId)

        saveReferrals()
    }

    // Remove referral
    fun removeReferral(referralId: UUID) {
        val inviterId = referrals.remove(referralId)
        if (inviterId != null) {
            val inviterList = inviterReferrals[inviterId]
            if (inviterList != null) {
                inviterList.remove(referralId)
                if (inviterList.isEmpty()) {
                    inviterReferrals.remove(inviterId)
                }
            }
        }

        referralStartTime.remove(referralId)
        referralRewarded.remove(referralId)

        saveReferrals()
    }

    // Check if player is a referral
    fun isReferral(playerId: UUID): Boolean {
        return referrals.containsKey(playerId)
    }

    // Get inviter of a referral
    fun getInviter(referralId: UUID): UUID? {
        return referrals[referralId]
    }

    // Get referrals of an inviter
    fun getReferrals(inviterId: UUID): List<UUID> {
        return inviterReferrals[inviterId] ?: emptyList()
    }

    // Start tracking time for referral
    fun startReferralTracking(referralId: UUID) {
        val now = System.currentTimeMillis()
        referralStartTime[referralId] = now
        referralRewarded[referralId] = false
        saveReferrals()

        plugin.logger.info("Started tracking time for referral $referralId at $now")
    }

    // Get start time for referral
    fun getReferralStartTime(referralId: UUID): Long? {
        return referralStartTime[referralId]
    }

    // Check if referral has been rewarded for 7 hours
    fun isReferralRewarded(referralId: UUID): Boolean {
        return referralRewarded[referralId] ?: false
    }

    // Mark referral as rewarded
    fun markReferralRewarded(referralId: UUID) {
        referralRewarded[referralId] = true
        saveReferrals()
    }

    // Get all active referrals (not rewarded yet)
    fun getActiveReferrals(): Map<UUID, Long> {
        val active = HashMap<UUID, Long>()
        for ((key, value) in referralStartTime) {
            if (!referralRewarded.getOrDefault(key, false)) {
                active[key] = value
            }
        }
        return active
    }
}
