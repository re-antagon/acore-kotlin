package org.antagon.acore.referral

import org.antagon.acore.referral.storage.ReferralDao
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReferralManager(
    private val plugin: JavaPlugin,
    private val referralDao: ReferralDao
) {
    private val referrals = ConcurrentHashMap<UUID, ReferralRecord>()
    private val inviterReferrals = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val pendingInvites = ConcurrentHashMap<UUID, UUID>()

    private val dbExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ACore-Referral-DB-Writer").apply { isDaemon = false }
    }

    init {
        migrateAndLoad()
    }

    private fun migrateAndLoad() {
        val referralFile = File(plugin.dataFolder, "referrals.yml")
        if (referralFile.exists()) {
            try {
                plugin.logger.info("Discovered legacy referrals.yml - starting migration to SQLite...")
                val count = referralDao.importFromYaml(referralFile)
                plugin.logger.info("Successfully imported $count referral records into SQLite.")

                val backupFile = File(plugin.dataFolder, "referrals.yml.migrated")
                try {
                    java.nio.file.Files.move(
                        referralFile.toPath(),
                        backupFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    plugin.logger.info("Renamed referrals.yml -> referrals.yml.migrated")
                } catch (moveEx: Exception) {
                    if (referralFile.renameTo(backupFile)) {
                        plugin.logger.info("Renamed referrals.yml -> referrals.yml.migrated (via fallback rename)")
                    } else {
                        plugin.logger.warning("Could not rename referrals.yml to referrals.yml.migrated: ${moveEx.message}. Please archive or delete it manually.")
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("Failed during legacy referrals.yml migration: ${e.message}")
            }
        }

        try {
            val records = referralDao.loadAll()
            for (record in records) {
                referrals[record.referralUuid] = record
                inviterReferrals.computeIfAbsent(record.inviterUuid) { ConcurrentHashMap.newKeySet() }
                    .add(record.referralUuid)
            }
            plugin.logger.info("Loaded ${referrals.size} referrals into memory from SQLite.")
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load referrals from SQLite: ${e.message}")
        }
    }

    fun addReferral(referralId: UUID, inviterId: UUID) {
        val record = ReferralRecord(
            referralUuid = referralId,
            inviterUuid = inviterId,
            startTime = 0L,
            isRewarded = false,
            isDirty = true
        )
        referrals[referralId] = record
        inviterReferrals.computeIfAbsent(inviterId) { ConcurrentHashMap.newKeySet() }.add(referralId)
        saveAsync(record)
    }

    fun removeReferral(referralId: UUID) {
        val record = referrals.remove(referralId)
        if (record != null) {
            inviterReferrals.computeIfPresent(record.inviterUuid) { _, set ->
                set.remove(referralId)
                if (set.isEmpty()) null else set
            }
            dbExecutor.submit {
                try {
                    referralDao.deleteReferral(referralId)
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to delete referral $referralId from database: ${e.message}")
                }
            }
        }
    }

    fun isReferral(playerId: UUID): Boolean {
        return referrals.containsKey(playerId)
    }

    fun getInviter(referralId: UUID): UUID? {
        return referrals[referralId]?.inviterUuid
    }

    fun getReferrals(inviterId: UUID): List<UUID> {
        return inviterReferrals[inviterId]?.toList() ?: emptyList()
    }

    fun startReferralTracking(referralId: UUID, startPlaytimeTicks: Long) {
        val record = referrals[referralId]
        if (record != null) {
            record.startTime = startPlaytimeTicks
            record.isRewarded = false
            record.isDirty = true
            saveAsync(record)
            plugin.logger.info("Started tracking time for referral $referralId at $startPlaytimeTicks ticks")
        }
    }

    fun getReferralStartTime(referralId: UUID): Long? {
        return referrals[referralId]?.startTime
    }

    fun isReferralRewarded(referralId: UUID): Boolean {
        return referrals[referralId]?.isRewarded ?: false
    }

    fun markReferralRewarded(referralId: UUID) {
        val record = referrals[referralId]
        if (record != null) {
            record.isRewarded = true
            record.isDirty = true
            saveAsync(record)
        }
    }

    fun getActiveReferrals(): Map<UUID, Long> {
        val active = HashMap<UUID, Long>()
        for ((key, record) in referrals) {
            if (!record.isRewarded) {
                active[key] = record.startTime
            }
        }
        return active
    }

    fun addPendingInvite(referralId: UUID, inviterId: UUID) {
        pendingInvites[referralId] = inviterId
    }

    fun getPendingInviter(referralId: UUID): UUID? {
        return pendingInvites[referralId]
    }

    fun removePendingInvite(referralId: UUID) {
        pendingInvites.remove(referralId)
    }

    fun saveAsync(record: ReferralRecord) {
        dbExecutor.submit {
            try {
                referralDao.saveReferral(record)
            } catch (e: Exception) {
                plugin.logger.severe("Failed async save for referral ${record.referralUuid}: ${e.message}")
            }
        }
    }

    fun shutdown() {
        plugin.logger.info("Flushing referral data cache to SQLite...")

        dbExecutor.shutdown()
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            dbExecutor.shutdownNow()
        }

        try {
            val dirtyRecords = referrals.values.filter { it.isDirty }
            if (dirtyRecords.isNotEmpty()) {
                referralDao.saveAll(dirtyRecords)
                plugin.logger.info("Saved ${dirtyRecords.size} dirty referral records synchronously during shutdown.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("Fatal error while flushing referral records during onDisable: ${e.message}")
        } finally {
            referrals.clear()
            inviterReferrals.clear()
            pendingInvites.clear()
        }
    }
}
