package org.antagon.acore.listener

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.DatabaseManager
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.util.DependencyHandler
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PlayerStreakData(
    val uuid: UUID,
    var currentStreak: Int = 0,
    var highestStreak: Int = 0,
    var totalLogins: Int = 0,
    var lastLoginDate: LocalDate? = null,
    var streakFreezes: Int = 0
) {
    @Volatile
    var isDirty: Boolean = false
}

class StreakDao(private val dbManager: DatabaseManager) {

    fun loadStreak(uuid: UUID): PlayerStreakData? {
        val query = "SELECT current_streak, highest_streak, total_logins, last_login_date, streak_freezes FROM player_streaks WHERE uuid = ?;"

        return dbManager.execute { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val currentStreak = rs.getInt("current_streak")
                        val highestStreak = rs.getInt("highest_streak")
                        val totalLogins = rs.getInt("total_logins")
                        val lastLoginStr = rs.getString("last_login_date")
                        val freezes = rs.getInt("streak_freezes")

                        val lastLogin = lastLoginStr?.let {
                            try {
                                LocalDate.parse(it)
                            } catch (_: Exception) {
                                null
                            }
                        }

                        PlayerStreakData(
                            uuid = uuid,
                            currentStreak = currentStreak,
                            highestStreak = highestStreak,
                            totalLogins = totalLogins,
                            lastLoginDate = lastLogin,
                            streakFreezes = freezes
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    fun saveStreak(data: PlayerStreakData) {
        val query = """
            INSERT INTO player_streaks (uuid, current_streak, highest_streak, total_logins, last_login_date, streak_freezes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                current_streak = excluded.current_streak,
                highest_streak = excluded.highest_streak,
                total_logins = excluded.total_logins,
                last_login_date = excluded.last_login_date,
                streak_freezes = excluded.streak_freezes;
        """.trimIndent()

        dbManager.execute { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setString(1, data.uuid.toString())
                stmt.setInt(2, data.currentStreak)
                stmt.setInt(3, data.highestStreak)
                stmt.setInt(4, data.totalLogins)
                stmt.setString(5, data.lastLoginDate?.toString())
                stmt.setInt(6, data.streakFreezes)
                stmt.executeUpdate()
            }
        }
        data.isDirty = false
    }

    fun saveAll(players: Collection<PlayerStreakData>) {
        if (players.isEmpty()) return

        val query = """
            INSERT INTO player_streaks (uuid, current_streak, highest_streak, total_logins, last_login_date, streak_freezes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                current_streak = excluded.current_streak,
                highest_streak = excluded.highest_streak,
                total_logins = excluded.total_logins,
                last_login_date = excluded.last_login_date,
                streak_freezes = excluded.streak_freezes;
        """.trimIndent()

        dbManager.execute { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(query).use { stmt ->
                    for (player in players) {
                        stmt.setString(1, player.uuid.toString())
                        stmt.setInt(2, player.currentStreak)
                        stmt.setInt(3, player.highestStreak)
                        stmt.setInt(4, player.totalLogins)
                        stmt.setString(5, player.lastLoginDate?.toString())
                        stmt.setInt(6, player.streakFreezes)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
                players.forEach { it.isDirty = false }
            } catch (e: SQLException) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }
}

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
        } else if (lastLogin == today) {
            // Re-login within the same calendar day
        } else if (lastLogin == today.minusDays(1)) {
            // Perfect consecutive day login
            data.currentStreak += 1
            data.totalLogins += 1
            if (data.currentStreak > data.highestStreak) {
                data.highestStreak = data.currentStreak
            }
            data.lastLoginDate = today
            data.isDirty = true
        } else {
            // Missed at least one calendar day
            val daysMissed = ChronoUnit.DAYS.between(lastLogin, today)
            if (daysMissed == 2L && data.streakFreezes > 0) {
                // Streak Freeze saves the streak!
                data.streakFreezes -= 1
                data.currentStreak += 1
                data.totalLogins += 1
                if (data.currentStreak > data.highestStreak) {
                    data.highestStreak = data.currentStreak
                }
                data.lastLoginDate = today
                data.isDirty = true
            } else {
                // Streak is reset
                data.currentStreak = 1
                data.totalLogins += 1
                data.lastLoginDate = today
                data.isDirty = true
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

class StreakPlaceholderExpansion(
    private val plugin: Acore,
    private val streakManager: StreakManager
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "acore"

    override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString(", ").ifEmpty { "re-antagon" }

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return ""
        val uuid = player.uniqueId
        val data = streakManager.getCachedData(uuid) ?: streakManager.loadOrInit(uuid)
        val today = streakManager.getEffectiveToday()

        return when (params.lowercase()) {
            "streak" -> data.currentStreak.toString()
            "streak_max" -> data.highestStreak.toString()
            "streak_total" -> data.totalLogins.toString()
            "streak_protection" -> data.streakFreezes.toString()
            "streak_last_login" -> data.lastLoginDate?.toString() ?: "Never"
            "streak_time_until_reset" -> streakManager.getTimeUntilReset()
            "streak_is_active_today" -> (data.lastLoginDate == today).toString()
            else -> null
        }
    }
}

class StreakListener(
    private val plugin: Plugin = Acore.instance,
    private val streakManager: StreakManager = Acore.instance.streakManager,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Daily Login Streak"

    private var expansion: StreakPlaceholderExpansion? = null

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("streak.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)

        DependencyHandler.executeSafely("PlaceholderAPI", "Streak Placeholders") {
            expansion = StreakPlaceholderExpansion(plugin as Acore, streakManager).apply {
                register()
            }
        }
    }

    override fun disable() {
        super.disable()
        expansion?.unregister()
        expansion = null
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        Acore.instance.streakManager.loadOrInit(event.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        Acore.instance.streakManager.processPlayerLogin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        Acore.instance.streakManager.unload(event.player.uniqueId)
    }
}
