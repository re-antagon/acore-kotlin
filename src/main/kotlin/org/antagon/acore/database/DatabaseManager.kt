package org.antagon.acore.database

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DatabaseManager(private val plugin: JavaPlugin) {

    private val dbFile = File(plugin.dataFolder, "data.db")
    private var connection: Connection? = null
    private val lock = ReentrantLock()

    fun initialize() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        try {
            Class.forName("org.sqlite.JDBC")
            val url = "jdbc:sqlite:${dbFile.absolutePath}"

            val properties = Properties().apply {
                setProperty("busy_timeout", "5000")
            }

            connection = DriverManager.getConnection(url, properties)

            // Configure essential performance & safety pragmas
            connection?.createStatement()?.use { stmt ->
                stmt.execute("PRAGMA busy_timeout = 5000;")
                stmt.execute("PRAGMA foreign_keys = ON;")
                stmt.execute("PRAGMA temp_store = MEMORY;")
                stmt.execute("PRAGMA synchronous = NORMAL;")
            }

            plugin.logger.info("SQLite database (data.db) connection established successfully.")

            runMigrations()
        } catch (e: Exception) {
            plugin.logger.severe("Failed to initialize SQLite database: ${e.message}")
            throw RuntimeException("Database initialization failure", e)
        }
    }

    private fun runMigrations() {
        execute { conn ->
            val currentVersion = conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA user_version;").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }

            if (currentVersion < 1) {
                val prevAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate(
                            """
                            CREATE TABLE IF NOT EXISTS player_streaks (
                                uuid VARCHAR(36) PRIMARY KEY NOT NULL,
                                current_streak INTEGER NOT NULL DEFAULT 0,
                                highest_streak INTEGER NOT NULL DEFAULT 0,
                                total_logins INTEGER NOT NULL DEFAULT 0,
                                last_login_date TEXT,
                                streak_freezes INTEGER NOT NULL DEFAULT 0
                            );
                            """.trimIndent()
                        )
                        stmt.executeUpdate("PRAGMA user_version = 1;")
                    }
                    conn.commit()
                    plugin.logger.info("Applied database migration v1 (player_streaks table).")
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = prevAutoCommit
                }
            }

            if (currentVersion < 2) {
                val prevAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate(
                            """
                            CREATE TABLE IF NOT EXISTS player_referrals (
                                referral_uuid VARCHAR(36) PRIMARY KEY NOT NULL,
                                inviter_uuid VARCHAR(36) NOT NULL,
                                start_time BIGINT NOT NULL DEFAULT 0,
                                rewarded INTEGER NOT NULL DEFAULT 0,
                                created_at TEXT DEFAULT CURRENT_TIMESTAMP
                            );
                            """.trimIndent()
                        )
                        stmt.executeUpdate(
                            """
                            CREATE INDEX IF NOT EXISTS idx_referrals_inviter ON player_referrals(inviter_uuid);
                            """.trimIndent()
                        )
                        stmt.executeUpdate("PRAGMA user_version = 2;")
                    }
                    conn.commit()
                    plugin.logger.info("Applied database migration v2 (player_referrals table).")
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = prevAutoCommit
                }
            }
        }
    }

    /**
     * Executes an operation safely using the synchronized SQLite connection.
     */
    fun <T> execute(block: (Connection) -> T): T {
        return lock.withLock {
            val conn = connection ?: throw IllegalStateException("Database connection is not open.")
            if (conn.isClosed) {
                throw IllegalStateException("Database connection was closed.")
            }
            block(conn)
        }
    }

    fun close() {
        lock.withLock {
            try {
                connection?.let { conn ->
                    if (!conn.isClosed) {
                        conn.close()
                    }
                }
                plugin.logger.info("SQLite database connection closed cleanly.")
            } catch (e: SQLException) {
                plugin.logger.severe("Error while closing SQLite database: ${e.message}")
            } finally {
                connection = null
            }
        }
    }
}
