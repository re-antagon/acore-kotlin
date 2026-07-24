package org.antagon.acore

import org.antagon.acore.commands.AcoreCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.fairplay.XaeroFairPlayManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.DependencyHandler
import org.antagon.acore.util.EntityKillTracker
import org.antagon.acore.util.ReferralManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class Acore : JavaPlugin() {

    companion object {
        lateinit var instance: Acore
            private set
    }

    lateinit var configManager: ConfigManager
        private set
    lateinit var referralManager: ReferralManager
        private set
    lateinit var xaeroFairPlayManager: XaeroFairPlayManager
        private set

    override fun onLoad() {
        instance = this
        xaeroFairPlayManager = XaeroFairPlayManager(this)
        xaeroFairPlayManager.onLoad()
    }

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

        // Initialize PacketEvents / XaeroFairPlayManager
        xaeroFairPlayManager.init()

        // Initialize referral manager
        referralManager = ReferralManager(this)

        // Register commands
        AcoreCommand(this, configManager, referralManager).register()

        // Auto-discover and enable all modules via reflection
        AcoreModule.reloadModules(this)

        // Start cleanup task for trackers
        startCleanupTask()

        logger.info("Acore plugin has been enabled successfully!")
    }

    private fun startCleanupTask() {
        object : BukkitRunnable() {
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
        server.scheduler.cancelTasks(this)

        AcoreModule.reloadModules(this)

        startCleanupTask()
        logger.info("Acore plugin has been reloaded successfully!")
    }

    private fun checkOptionalDependencies() {
        val softDeps = listOf("LuckPerms", "ConditionalEvents", "MythicMobs", "PacketEvents")
        for (dep in softDeps) {
            if (DependencyHandler.isPluginEnabled(dep)) {
                logger.info("Optional dependency '$dep' found and enabled.")
            } else {
                logger.info("Optional dependency '$dep' not found. Related features will be safely disabled.")
            }
        }
    }

    override fun onDisable() {
        AcoreModule.disableAll(this)
        xaeroFairPlayManager.terminate()
        logger.info("Acore plugin has been disabled")
    }
}