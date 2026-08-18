package org.antagon.acore.streak.storage

import org.antagon.acore.database.DatabaseManager
import org.antagon.acore.streak.PlayerStreakData
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

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
