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
    private val pendingInvites: MutableMap<UUID, UUID> = HashMap() // реферал -> пригласивший (не сохраняется в файл)
    private lateinit var referralConfig: YamlConfiguration

    init {
        referralFile = File(plugin.dataFolder, "referrals.yml")
        loadReferrals()
    }

    // Load referrals from file - FIXED: robust handling of Integer vs Long and missing sections
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
        referralConfig.getConfigurationSection("referrals")?.getValues(false)?.let { map ->
            for ((key, value) in map) {
                try {
                    val referralId = UUID.fromString(key)
                    val inviterStr = value as? String
                    if (inviterStr == null) {
                        plugin.logger.warning("Invalid value type in referrals file for $key: ${value?.javaClass?.name}")
                        continue
                    }
                    val inviterId = UUID.fromString(inviterStr)
                    referrals[referralId] = inviterId
                } catch (e: Exception) {
                    plugin.logger.warning("Invalid UUID in referrals file: $key -> $value (${e.message})")
                }
            }
        }

        // Load inviter referrals map
        referralConfig.getConfigurationSection("inviter-referrals")?.let { section ->
            for (key in section.getKeys(false)) {
                try {
                    val inviterId = UUID.fromString(key)
                    val referralIds = section.getStringList(key)
                    val referralUUIDs = mutableListOf<UUID>()
                    for (id in referralIds) {
                        try {
                            referralUUIDs.add(UUID.fromString(id))
                        } catch (ex: IllegalArgumentException) {
                            plugin.logger.warning("Invalid referral UUID in inviter-referrals for $key: $id")
                        }
                    }
                    if (referralUUIDs.isNotEmpty()) {
                        inviterReferrals[inviterId] = referralUUIDs
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Invalid UUID in inviter referrals file: $key (${e.message})")
                }
            }
        }

        // Load start times
        referralConfig.getConfigurationSection("referral-start-times")?.getValues(false)?.let { map ->
            for ((key, value) in map) {
                try {
                    val referralId = UUID.fromString(key)
                    val startTime: Long? = when (value) {
                        is Long -> value
                        is Int -> value.toLong()
                        is Number -> value.toLong() // covers Short, Byte, Double, etc.
                        is String -> value.toLongOrNull()
                        else -> null
                    }
                    if (startTime != null) {
                        referralStartTime[referralId] = startTime
                    } else {
                        plugin.logger.warning("Invalid start time value for $key: $value (${value?.javaClass?.name})")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Invalid entry in referral start times file: $key (${e.message})")
                }
            }
        }

        // Load rewarded status
        referralConfig.getConfigurationSection("referral-rewarded")?.getValues(false)?.let { map ->
            for ((key, value) in map) {
                try {
                    val referralId = UUID.fromString(key)
                    val rewarded: Boolean? = when (value) {
                        is Boolean -> value
                        is String -> value.toBooleanStrictOrNull() ?: value.equals("true", ignoreCase = true)
                        is Number -> value.toInt() != 0
                        else -> null
                    }
                    if (rewarded != null) {
                        referralRewarded[referralId] = rewarded
                    } else {
                        plugin.logger.warning("Invalid rewarded value for $key: $value (${value?.javaClass?.name})")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Invalid entry in referral rewarded file: $key (${e.message})")
                }
            }
        }

        plugin.logger.info("Loaded " + referrals.size + " referrals")
    }

    // Save referrals to file
    private fun saveReferrals() {
        // Create snapshots of the maps on the main thread to ensure thread safety
        val referralsSnapshot = HashMap(referrals)
        val inviterReferralsSnapshot = HashMap(inviterReferrals.mapValues { ArrayList(it.value) })
        val referralStartTimeSnapshot = HashMap(referralStartTime)
        val referralRewardedSnapshot = HashMap(referralRewarded)

        // Run the file saving task asynchronously
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            synchronized(referralFile) { // Ensure only one thread writes to the file at a time
                val config = YamlConfiguration()

                // Save referrals map
                val referralsMap = HashMap<String, String>()
                for ((key, value) in referralsSnapshot) {
                    referralsMap[key.toString()] = value.toString()
                }
                config.set("referrals", referralsMap)

                // Save inviter referrals map
                val inviterMap = HashMap<String, List<String>>()
                for ((key, value) in inviterReferralsSnapshot) {
                    val referralIds = value.map { it.toString() }
                    inviterMap[key.toString()] = referralIds
                }
                config.set("inviter-referrals", inviterMap)

                // Save start times
                val startTimesMap = HashMap<String, Long>()
                for ((key, value) in referralStartTimeSnapshot) {
                    startTimesMap[key.toString()] = value
                }
                config.set("referral-start-times", startTimesMap)

                // Save rewarded status
                val rewardedMap = HashMap<String, Boolean>()
                for ((key, value) in referralRewardedSnapshot) {
                    rewardedMap[key.toString()] = value
                }
                config.set("referral-rewarded", rewardedMap)

                try {
                    config.save(referralFile)
                } catch (e: IOException) {
                    plugin.logger.severe("Failed to save referrals file asynchronously: " + e.message)
                }
            }
        })
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
    fun startReferralTracking(referralId: UUID, startPlaytimeTicks: Long) {
        referralStartTime[referralId] = startPlaytimeTicks
        referralRewarded[referralId] = false
        saveReferrals()

        plugin.logger.info("Started tracking time for referral $referralId at $startPlaytimeTicks ticks")
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

    // Pending invites management
    fun addPendingInvite(referralId: UUID, inviterId: UUID) {
        pendingInvites[referralId] = inviterId
    }

    fun getPendingInviter(referralId: UUID): UUID? {
        return pendingInvites[referralId]
    }

    fun removePendingInvite(referralId: UUID) {
        pendingInvites.remove(referralId)
    }
}
