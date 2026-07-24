package org.antagon.acore

import org.antagon.acore.commands.AcoreCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.fairplay.XaeroFairPlayManager
import org.antagon.acore.listener.*
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.EntityKillTracker
import org.antagon.acore.util.ReferralManager
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

import org.antagon.acore.util.DependencyHandler

class Acore : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var referralManager: ReferralManager
    private lateinit var xaeroFairPlayManager: XaeroFairPlayManager

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

        // Register listeners
        registerListeners()

        // Start cleanup task for trackers
        startCleanupTask()

        logger.info("Acore plugin has been enabled successfully!")
    }

    private fun registerListeners() {
        // Register XaeroFairPlayListener if enabled in config
        if (configManager.getBoolean("xaeroFairPlay.enabled", true)) {
            server.pluginManager.registerEvents(
                XaeroFairPlayListener(this, xaeroFairPlayManager, configManager), this)
            logger.info("Xaero's Minimap Fair-Play feature enabled")
        }

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

        // Register MultishotCrossbowListener if enabled in config
        if (configManager.getBoolean("multishotImprovement.enabled", true)) {
            server.pluginManager.registerEvents(MultishotCrossbowListener(this, configManager), this)
            logger.info("Multishot Crossbow damage improvement feature enabled")
        }

        // Register SoulSoilTillListener if enabled in config
        if (configManager.getBoolean("soulSoilTill.enabled", true)) {
            server.pluginManager.registerEvents(SoulSoilTillListener(configManager), this)
            logger.info("Soul Soil Till feature enabled")
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
        xaeroFairPlayManager.terminate()
        // Plugin shutdown logic
        logger.info("Acore plugin has been disabled")
    }
}