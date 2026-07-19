package org.antagon.acore

import org.antagon.acore.commands.AcoreCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.listener.*
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.EntityKillTracker
import org.antagon.acore.util.ReferralManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class Acore : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var referralManager: ReferralManager

    override fun onEnable() {
        if (!dataFolder.exists()) {
            if (dataFolder.mkdirs()) {
                logger.info("Created plugin data folder: ${dataFolder.absolutePath}")
            } else {
                logger.severe("Failed to create plugin data folder at ${dataFolder.absolutePath}!")
            }
        }

        // Initialize config
        configManager = ConfigManager.initialize(dataFolder, logger)

        // Initialize referral manager
        referralManager = ReferralManager(this)

        // Register commands
        AcoreCommand(this, configManager, referralManager).register()

        // Register listeners
        registerListeners()

        // Start cleanup task for trackers
        startCleanupTask()

        logger.info("Acore plugin has been enabled successfully!")
    }

    private fun registerListeners() {
        // Register VillagerTransportListener if enabled in config
        if (configManager.getBoolean("villagerTransport.enabled", true)) {
            server.pluginManager.registerEvents(
                VillagerTransportListener(this, configManager), this)
            logger.info("Villager Transportation feature enabled")
        }

        // Register MinecartSpeedListener if enabled in config
        if (configManager.getBoolean("minecartSpeed.enabled", true)) {
            server.pluginManager.registerEvents(MinecartSpeedListener(), this)
            logger.info("Minecart Speed feature enabled")
        }

        server.pluginManager.registerEvents(ItemFrameListener(configManager), this)

        // Register BlockInteractionListener for tracking player block interactions
        server.pluginManager.registerEvents(BlockInteractionListener(), this)
        logger.info("Block Interaction Tracker enabled")

        // Register EntityKillListener for tracking player entity kills
        server.pluginManager.registerEvents(EntityKillListener(), this)
        logger.info("Entity Kill Tracker enabled")

        // Register IndicatorPotionListener if enabled in config
        if (configManager.getBoolean("indicatorPotions.enabled", true)) {
            server.pluginManager.registerEvents(IndicatorPotionListener(this, configManager), this)
            logger.info("Indicator Potions feature enabled")
        }

        // Register BannerHeadListener if enabled in config
        if (configManager.getBoolean("bannerHead.enabled", true)) {
            server.pluginManager.registerEvents(BannerHeadListener(configManager), this)
            logger.info("Banner Head feature enabled")
        }

        // Register PlayerJoinListener if enabled in config
        if (configManager.getBoolean("firstJoinItem.enabled", true)) {
            server.pluginManager.registerEvents(PlayerJoinListener(), this)
            logger.info("First Join Item feature enabled")
        }

        // Register ReferralListener if enabled in config
        if (configManager.getBoolean("referrals.enabled", true)) {
            server.pluginManager.registerEvents(ReferralListener(this, referralManager), this)
            logger.info("Referral feature enabled")
        }

        // Register StonecutterBlockProcessorListener if enabled in config
        if (configManager.getBoolean("stonecutterBlockProcessor.enabled", true)) {
            server.pluginManager.registerEvents(StonecutterBlockProcessorListener(this, configManager), this)
            logger.info("Stonecutter Block Processor feature enabled")
        }

        // Register AnvilFallListener if enabled in config
        if (configManager.getBoolean("anvilFall.enabled", true)) {
            server.pluginManager.registerEvents(AnvilFallListener(this), this)
            logger.info("Anvil Fall Listener feature enabled")
        }

        // Register pistonLaunchAnvil if enabled in config
        if (configManager.getBoolean("pistonLaunchAnvil.enabled", true)) {
            server.pluginManager.registerEvents(PistonLaunchAnvilListener(this), this)
            logger.info("Piston Launch Anvil Listener feature enabled")
        }

        // Fire drops and lightning block conversions
        if (configManager.getBoolean("fireAdjustment.enabled", true)) {
            server.pluginManager.registerEvents(BlockBurnListener(configManager), this)
            logger.info("Fire adjustment feature enabled")
        }
        if (configManager.getBoolean("lightningConversion.enabled", true)) {
            server.pluginManager.registerEvents(LightningConversionListener(configManager), this)
            logger.info("Lightning conversion feature enabled")
        }

        // Register CopperOxidationListener if enabled in config
        if (configManager.getBoolean("copperOxidation.enabled", true)) {
            server.pluginManager.registerEvents(CopperOxidationListener(this, configManager), this)
            logger.info("Copper Oxidation acceleration feature enabled")
        }
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

        // 2. Unregister all event listeners registered by this plugin
        org.bukkit.event.HandlerList.unregisterAll(this)

        // 3. Cancel all tasks registered by this plugin
        server.scheduler.cancelTasks(this)

        // 4. Re-register listeners
        registerListeners()

        // 5. Restart cleanup task
        startCleanupTask()

        logger.info("Acore plugin has been reloaded successfully!")
    }

    override fun onDisable() {
        // Plugin shutdown logic
        logger.info("Acore plugin has been disabled")
    }
}