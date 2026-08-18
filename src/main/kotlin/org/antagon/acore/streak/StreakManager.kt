package org.antagon.acore.streak

import org.antagon.acore.core.ConfigManager
import org.antagon.acore.streak.event.PlayerStreakIncrementEvent
import org.antagon.acore.streak.event.PlayerStreakResetEvent
import org.antagon.acore.streak.storage.StreakDao
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreakManager(
    private val plugin: JavaPlugin,
    private val streakDao: StreakDao,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) {
    private val cache = ConcurrentHashMap<UUID, PlayerStreakData>()

    private val dbExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ACore-Streak-DB-Writer").apply { isDaemon = false }
    }

    fun getZoneId(): ZoneId {
        val zoneStr = configManager.getString("streak.timezone", "Europe/Moscow")
        return try {
            ZoneId.of(zoneStr)
        } catch (_: Exception) {
            ZoneId.of("Europe/Moscow")
        }
    }

    fun getResetHour(): Int {
        return configManager.getInt("streak.reset-hour", 0).coerceIn(0, 23)
    }

    fun getEffectiveToday(): LocalDate {
        val zone = getZoneId()
        val resetHour = getResetHour()
        return LocalDateTime.now(zone).minusHours(resetHour.toLong()).toLocalDate()
    }

    fun getNextResetDateTime(): LocalDateTime {
        val zone = getZoneId()
        val resetHour = getResetHour()
        val now = LocalDateTime.now(zone)
        val todayReset = now.toLocalDate().atTime(resetHour, 0)
        return if (now.isBefore(todayReset)) todayReset else todayReset.plusDays(1)
    }

    fun getTimeUntilReset(): String {
        val zone = getZoneId()
        val now = LocalDateTime.now(zone)
        val nextReset = getNextResetDateTime()
        val duration = Duration.between(now, nextReset)
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun getCachedData(uuid: UUID): PlayerStreakData? = cache[uuid]

    fun loadOrInit(uuid: UUID): PlayerStreakData {
        val data = try {
            streakDao.loadStreak(uuid) ?: PlayerStreakData(uuid).also {
                it.isDirty = true
                streakDao.saveStreak(it)
            }
        } catch (e: Exception) {
            plugin.logger.severe("Could not load streak data for $uuid: ${e.message}")
            PlayerStreakData(uuid)
        }
        cache[uuid] = data
        return data
    }

    fun unload(uuid: UUID) {
        val data = cache.remove(uuid) ?: return
        dbExecutor.submit {
            try {
                streakDao.saveStreak(data)
            } catch (e: Exception) {
                plugin.logger.severe("Failed to save streak data on quit for $uuid: ${e.message}")
            }
        }
    }

    fun processPlayerLogin(player: Player) {
        val data = cache.computeIfAbsent(player.uniqueId) { loadOrInit(player.uniqueId) }
        val today = getEffectiveToday()
        val lastLogin = data.lastLoginDate

        if (lastLogin == null) {
            // First time ever joining
            data.currentStreak = 1
            data.highestStreak = 1
            data.totalLogins = 1
            data.lastLoginDate = today
            data.isDirty = true

            Bukkit.getPluginManager().callEvent(
                PlayerStreakIncrementEvent(player, data, 0, 1, false)
            )
        } else if (lastLogin == today) {
            // Re-login within the same calendar day
        } else if (lastLogin == today.minusDays(1)) {
            // Perfect consecutive day login
            val prevStreak = data.currentStreak
            data.currentStreak += 1
            data.totalLogins += 1
            if (data.currentStreak > data.highestStreak) {
                data.highestStreak = data.currentStreak
            }
            data.lastLoginDate = today
            data.isDirty = true

            Bukkit.getPluginManager().callEvent(
                PlayerStreakIncrementEvent(player, data, prevStreak, data.currentStreak, false)
            )
        } else {
            // Missed at least one calendar day
            val daysMissed = ChronoUnit.DAYS.between(lastLogin, today)
            if (daysMissed == 2L && data.streakFreezes > 0) {
                // Streak Freeze saves the streak!
                data.streakFreezes -= 1
                val prevStreak = data.currentStreak
                data.currentStreak += 1
                data.totalLogins += 1
                if (data.currentStreak > data.highestStreak) {
                    data.highestStreak = data.currentStreak
                }
                data.lastLoginDate = today
                data.isDirty = true

                Bukkit.getPluginManager().callEvent(
                    PlayerStreakIncrementEvent(player, data, prevStreak, data.currentStreak, true)
                )
            } else {
                // Streak is reset
                val prevStreak = data.currentStreak
                data.currentStreak = 1
                data.totalLogins += 1
                data.lastLoginDate = today
                data.isDirty = true

                Bukkit.getPluginManager().callEvent(
                    PlayerStreakResetEvent(player, data, prevStreak, 1)
                )
            }
        }

        saveAsync(data)
    }

    fun saveAsync(data: PlayerStreakData) {
        dbExecutor.submit {
            try {
                streakDao.saveStreak(data)
            } catch (e: Exception) {
                plugin.logger.severe("Failed async save for streak ${data.uuid}: ${e.message}")
            }
        }
    }

    fun setStreak(uuid: UUID, amount: Int): PlayerStreakData {
        val data = cache[uuid] ?: loadOrInit(uuid)
        data.currentStreak = amount.coerceAtLeast(0)
        if (data.currentStreak > data.highestStreak) {
            data.highestStreak = data.currentStreak
        }
        data.isDirty = true
        saveAsync(data)
        return data
    }

    fun resetStreak(uuid: UUID): PlayerStreakData {
        val data = cache[uuid] ?: loadOrInit(uuid)
        data.currentStreak = 0
        data.lastLoginDate = null
        data.isDirty = true
        saveAsync(data)
        return data
    }

    fun addFreezes(uuid: UUID, amount: Int): PlayerStreakData {
        val data = cache[uuid] ?: loadOrInit(uuid)
        data.streakFreezes = (data.streakFreezes + amount).coerceAtLeast(0)
        data.isDirty = true
        saveAsync(data)
        return data
    }

    fun setFreezes(uuid: UUID, amount: Int): PlayerStreakData {
        val data = cache[uuid] ?: loadOrInit(uuid)
        data.streakFreezes = amount.coerceAtLeast(0)
        data.isDirty = true
        saveAsync(data)
        return data
    }

    fun shutdown() {
        plugin.logger.info("Flushing streak data cache to SQLite...")

        dbExecutor.shutdown()
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            dbExecutor.shutdownNow()
        }

        try {
            val allData = cache.values
            if (allData.isNotEmpty()) {
                streakDao.saveAll(allData)
                plugin.logger.info("Saved ${allData.size} streak profiles synchronously during shutdown.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("Fatal error while flushing streak profiles during onDisable: ${e.message}")
        } finally {
            cache.clear()
        }
    }
}
