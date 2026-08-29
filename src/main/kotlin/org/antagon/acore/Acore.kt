package org.antagon.acore

import org.antagon.acore.commands.AcoreCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.DatabaseManager
import org.antagon.acore.core.LocalizationManager
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.feature.referral.ReferralDao
import org.antagon.acore.feature.referral.ReferralManager
import org.antagon.acore.feature.streak.StreakDao
import org.antagon.acore.feature.streak.StreakManager
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.DependencyHandler
import org.antagon.acore.util.EntityKillTracker
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class Acore : JavaPlugin() {

    companion object {
        lateinit var instance: Acore
            private set
    }

    lateinit var configManager: ConfigManager
        private set
    lateinit var localizationManager: LocalizationManager
        private set
    lateinit var referralManager: ReferralManager
        private set
    lateinit var databaseManager: DatabaseManager
        private set
    lateinit var streakManager: StreakManager
        private set

    override fun onEnable() {
        instance = this
        if (!dataFolder.exists()) {
            if (dataFolder.mkdirs()) {
                logger.info("Created plugin data folder: ${dataFolder.absolutePath}")
            } else {
                logger.severe("Failed to create plugin data folder at ${dataFolder.absolutePath}!")
            }
        }

        // Check optional dependencies
        checkOptionalDependencies()

        // Initialize config
        configManager = ConfigManager.initialize(dataFolder, logger)

        // Initialize LocalizationManager
        localizationManager = LocalizationManager(
            this,
            java.io.File(dataFolder, "language"),
            configManager.getString("language", "ru-RU")
        )
        localizationManager.loadLanguages()

        // Initialize SQLite database
        databaseManager = DatabaseManager(this)
        databaseManager.initialize()

        // Initialize StreakManager
        val streakDao = StreakDao(databaseManager)
        streakManager = StreakManager(this, streakDao, configManager)

        // Initialize referral manager
        val referralDao = ReferralDao(databaseManager)
        referralManager = ReferralManager(this, referralDao)

        // Register commands
        AcoreCommand(this, configManager, referralManager, streakManager).register()

        // Auto-discover and enable all modules via reflection
        AcoreModule.reloadModules(this)

        // Start cleanup task for trackers
        startCleanupTask()

        localizationManager.sendConsole("enable-success")
    }

    private var cleanupTask: org.bukkit.scheduler.BukkitTask? = null

    private fun startCleanupTask() {
        cleanupTask?.cancel()
        cleanupTask = object : BukkitRunnable() {
            override fun run() {
                try {
                    BlockInteractionTracker.getInstance().cleanupOldInteractions()
                    EntityKillTracker.getInstance().cleanupOldKills()
                    logger.info("Trackers cleanup completed successfully.")
                } catch (e: Exception) {
                    logger.warning("Failed to run trackers cleanup: " + e.message)
                }
            }
        }.runTaskTimer(this, 12000L, 72000L)
    }

    fun reloadPlugin() {
        configManager.reload()
        localizationManager.loadLanguages()
        server.scheduler.cancelTasks(this)

        AcoreModule.reloadModules(this)

        startCleanupTask()
        localizationManager.sendConsole("reload")
    }

    private fun checkOptionalDependencies() {
        val softDeps = listOf("LuckPerms", "ConditionalEvents", "MythicMobs", "PacketEvents", "PlaceholderAPI")
        for (dep in softDeps) {
            if (DependencyHandler.isPluginEnabled(dep)) {
                logger.info("Optional dependency '$dep' found and enabled.")
            } else {
                logger.info("Optional dependency '$dep' not found. Related features will be safely disabled.")
            }
        }
    }

    override fun onDisable() {
        cleanupTask?.cancel()
        cleanupTask = null
        AcoreModule.disableAll(this)
        streakManager.shutdown()
        referralManager.shutdown()
        databaseManager.close()
        if (::localizationManager.isInitialized) {
            localizationManager.sendConsole("disable-success")
            localizationManager.unregisterTranslator()
        } else {
            logger.info("Acore plugin has been disabled")
        }
    }
}