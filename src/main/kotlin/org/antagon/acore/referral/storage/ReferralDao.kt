package org.antagon.acore.referral.storage

import org.antagon.acore.database.DatabaseManager
import org.antagon.acore.referral.ReferralRecord
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.sql.SQLException
import java.util.UUID

class ReferralDao(private val dbManager: DatabaseManager) {

    fun loadAll(): List<ReferralRecord> {
        val query = "SELECT referral_uuid, inviter_uuid, start_time, rewarded FROM player_referrals;"

        return dbManager.execute { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<ReferralRecord>()
                    while (rs.next()) {
                        val refUuidStr = rs.getString("referral_uuid")
                        val invUuidStr = rs.getString("inviter_uuid")
                        val startTime = rs.getLong("start_time")
                        val rewarded = rs.getInt("rewarded") != 0

                        try {
                            val refUuid = UUID.fromString(refUuidStr)
                            val invUuid = UUID.fromString(invUuidStr)
                            results.add(
                                ReferralRecord(
                                    referralUuid = refUuid,
                                    inviterUuid = invUuid,
                                    startTime = startTime,
                                    isRewarded = rewarded,
                                    isDirty = false
                                )
                            )
                        } catch (_: IllegalArgumentException) {
                            // Skip any malformed UUIDs
                        }
                    }
                    results
                }
            }
        }
    }

    fun saveReferral(record: ReferralRecord) {
        val query = """
            INSERT INTO player_referrals (referral_uuid, inviter_uuid, start_time, rewarded)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(referral_uuid) DO UPDATE SET
                inviter_uuid = excluded.inviter_uuid,
                start_time = excluded.start_time,
                rewarded = excluded.rewarded;
        """.trimIndent()

        dbManager.execute { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setString(1, record.referralUuid.toString())
                stmt.setString(2, record.inviterUuid.toString())
                stmt.setLong(3, record.startTime)
                stmt.setInt(4, if (record.isRewarded) 1 else 0)
                stmt.executeUpdate()
            }
        }
        record.isDirty = false
    }

    fun saveAll(records: Collection<ReferralRecord>) {
        if (records.isEmpty()) return

        val query = """
            INSERT INTO player_referrals (referral_uuid, inviter_uuid, start_time, rewarded)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(referral_uuid) DO UPDATE SET
                inviter_uuid = excluded.inviter_uuid,
                start_time = excluded.start_time,
                rewarded = excluded.rewarded;
        """.trimIndent()

        dbManager.execute { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(query).use { stmt ->
                    for (record in records) {
                        stmt.setString(1, record.referralUuid.toString())
                        stmt.setString(2, record.inviterUuid.toString())
                        stmt.setLong(3, record.startTime)
                        stmt.setInt(4, if (record.isRewarded) 1 else 0)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
                records.forEach { it.isDirty = false }
            } catch (e: SQLException) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    fun deleteReferral(referralUuid: UUID) {
        val query = "DELETE FROM player_referrals WHERE referral_uuid = ?;"

        dbManager.execute { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setString(1, referralUuid.toString())
                stmt.executeUpdate()
            }
        }
    }

    fun importFromYaml(file: File): Int {
        if (!file.exists()) return 0
        val config = YamlConfiguration.loadConfiguration(file)

        // 1. referrals map: referral_uuid -> inviter_uuid
        val referralsMap = mutableMapOf<UUID, UUID>()
        config.getConfigurationSection("referrals")?.getValues(false)?.forEach { (key, value) ->
            try {
                val referralId = UUID.fromString(key.trim())
                val inviterStr = (value as? String ?: value?.toString())?.trim() ?: return@forEach
                val inviterId = UUID.fromString(inviterStr)
                referralsMap[referralId] = inviterId
            } catch (_: Exception) {
            }
        }

        // Also check inviter-referrals
        config.getConfigurationSection("inviter-referrals")?.let { section ->
            for (key in section.getKeys(false)) {
                try {
                    val inviterId = UUID.fromString(key.trim())
                    val referralList = section.getStringList(key)
                    for (refStr in referralList) {
                        try {
                            val refId = UUID.fromString(refStr.trim())
                            referralsMap.putIfAbsent(refId, inviterId)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (referralsMap.isEmpty()) return 0

        // 2. start times map
        val startTimesMap = mutableMapOf<UUID, Long>()
        config.getConfigurationSection("referral-start-times")?.getValues(false)?.forEach { (key, value) ->
            try {
                val referralId = UUID.fromString(key.trim())
                val startTime: Long? = when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Number -> value.toLong()
                    is String -> value.trim().toLongOrNull()
                    else -> null
                }
                if (startTime != null) {
                    startTimesMap[referralId] = startTime
                }
            } catch (_: Exception) {
            }
        }

        // 3. rewarded status map
        val rewardedMap = mutableMapOf<UUID, Boolean>()
        config.getConfigurationSection("referral-rewarded")?.getValues(false)?.forEach { (key, value) ->
            try {
                val referralId = UUID.fromString(key.trim())
                val rewarded: Boolean? = when (value) {
                    is Boolean -> value
                    is String -> value.trim().toBooleanStrictOrNull() ?: value.trim().equals("true", ignoreCase = true)
                    is Number -> value.toInt() != 0
                    else -> null
                }
                if (rewarded != null) {
                    rewardedMap[referralId] = rewarded
                }
            } catch (_: Exception) {
            }
        }

        val records = referralsMap.map { (referralId, inviterId) ->
            ReferralRecord(
                referralUuid = referralId,
                inviterUuid = inviterId,
                startTime = startTimesMap[referralId] ?: 0L,
                isRewarded = rewardedMap[referralId] ?: false,
                isDirty = false
            )
        }

        saveAll(records)
        return records.size
    }
}
