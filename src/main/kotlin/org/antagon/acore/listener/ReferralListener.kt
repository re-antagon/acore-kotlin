package org.antagon.acore.listener

import net.kyori.adventure.text.minimessage.MiniMessage
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.database.DatabaseManager
import org.antagon.acore.core.module.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ReferralRecord(
    val referralUuid: UUID,
    val inviterUuid: UUID,
    var startTime: Long = 0L,
    var isRewarded: Boolean = false,
    @Volatile var isDirty: Boolean = false
)

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
        }.runTaskTimer(plugin, 1200L, 1200L)
    }

    private fun checkReferralTimes() {
        val sevenHoursTicks = 7 * 60 * 60 * 20L

        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.uniqueId

            if (referralManager.isReferral(playerId) && !referralManager.isReferralRewarded(playerId)) {
                val startTime = referralManager.getReferralStartTime(playerId)
                if (startTime != null) {
                    val currentPlaytime = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE).toLong()
                    
                    val isOldTimestamp = startTime > 1_000_000_000_000L
                    val playedTime = if (isOldTimestamp) {
                        System.currentTimeMillis() - startTime
                    } else {
                        currentPlaytime - startTime
                    }
                    
                    val requiredTime = if (isOldTimestamp) {
                        7 * 60 * 60 * 1000L
                    } else {
                        sevenHoursTicks
                    }

                    if (playedTime >= requiredTime) {
                        val inviterId = referralManager.getInviter(playerId)
                        if (inviterId != null) {
                            val inviter = Bukkit.getPlayer(inviterId)
                            if (inviter != null) {
                                giveReward(inviter, 9)
                                inviter.sendMessage(mm.deserialize("<green>Ваш реферал <yellow>${player.name}</yellow> отыграл 7 часов! Вы получили награду.</green>"))
                            }
                        }

                        referralManager.markReferralRewarded(playerId)
                        player.sendMessage(mm.deserialize("<green>Вы отыграли 7 часов как реферал! Ваш пригласивший получил награду.</green>"))
                        plugin.logger.info("Referral ${player.name} completed 7 hours playtime")
                    }
                }
            }
        }
    }

    private fun handleAccept(referral: Player, inviterName: String, referralName: String) {
        if (referral.name != referralName) {
            return
        }

        val inviter = Bukkit.getPlayer(inviterName)
        if (inviter == null) {
            referral.sendMessage(mm.deserialize("<color:#fc5454>Пригласивший игрок не найден!</color:#fc5454>"))
            return
        }

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

        if (referralManager.isReferral(referral.uniqueId)) {
            referral.sendMessage(mm.deserialize("<color:#fc5454>Вы уже являетесь рефералом!</color:#fc5454>"))
            referralManager.removePendingInvite(referral.uniqueId)
            return
        }

        referralManager.addReferral(referral.uniqueId, inviter.uniqueId)
        referralManager.removePendingInvite(referral.uniqueId)

        giveReward(inviter, 1)

        val currentPlaytime = referral.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE).toLong()
        referralManager.startReferralTracking(referral.uniqueId, currentPlaytime)

        referral.sendMessage(mm.deserialize("<green>Вы приняли приглашение от <yellow>$inviterName</yellow>!</green>"))
        inviter.sendMessage(mm.deserialize("<green>Игрок <yellow>$referralName</yellow> принял ваше приглашение!</green>"))

        plugin.logger.info("Player $referralName accepted referral from $inviterName")
    }

    private fun handleDecline(referral: Player, inviterName: String, referralName: String) {
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