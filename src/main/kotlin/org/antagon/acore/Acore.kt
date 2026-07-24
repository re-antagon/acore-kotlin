package org.antagon.acore

import org.antagon.acore.commands.AcoreCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.fairplay.XaeroFairPlayManager
import org.antagon.acore.listener.*
import org.antagon.acore.module.ModuleManager
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.DependencyHandler
import org.antagon.acore.util.EntityKillTracker
import org.antagon.acore.util.ReferralManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class Acore : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var referralManager: ReferralManager
    private lateinit var xaeroFairPlayManager: XaeroFairPlayManager
    private lateinit var moduleManager: ModuleManager

    override fun onLoad() {
        xaeroFairPlayManager = XaeroFairPlayManager(this)
        xaeroFairPlayManager.onLoad()
    }

    override fun onEnable() {
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

        // Initialize & load feature modules
        setupModules()

        // Start cleanup task for trackers
        startCleanupTask()

        logger.info("Acore plugin has been enabled successfully!")
    }

    private fun setupModules() {
        moduleManager = ModuleManager(this)
        moduleManager.registerModules(
            XaeroFairPlayListener(this, xaeroFairPlayManager, configManager),
            VillagerTransportListener(this, configManager),
            MinecartSpeedListener(this, configManager),
            ItemFrameListener(this, configManager),
            BlockInteractionListener(this),
            EntityKillListener(this),
            IndicatorPotionListener(this, configManager),
            FogPotionListener(this, configManager),
            BannerHeadListener(this, configManager),
            PlayerJoinListener(this, configManager),
            ReferralListener(this, referralManager, configManager),
            StonecutterBlockProcessorListener(this, configManager),
            AnvilFallListener(this, configManager),
            PistonLaunchAnvilListener(this, configManager),
            BlockBurnListener(this, configManager),
            LightningConversionListener(this, configManager),
            CopperOxidationListener(this, configManager),
            MultishotCrossbowListener(this, configManager),
            SoulSoilTillListener(this, configManager)
        )
        moduleManager.loadModules()
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
        }.runTaskTimer(this, 12000L, 72000L) // Run every hour (72000 ticks), start after 10 minutes (12000 ticks)
    }

    fun reloadPlugin() {
        // 1. Reload the configuration manager
        configManager.reload()

        // 2. Cancel scheduled tasks
        server.scheduler.cancelTasks(this)

        // 3. Reload modules (disables old modules and loads active ones)
        moduleManager.reloadModules()

        // 4. Restart cleanup task
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
        if (::moduleManager.isInitialized) {
            moduleManager.disableModules()
        }
        xaeroFairPlayManager.terminate()
        logger.info("Acore plugin has been disabled")
    }
}