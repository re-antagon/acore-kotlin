package org.antagon.acore

import org.antagon.acore.commands.LinkCommand
import org.antagon.acore.commands.ShowInfoCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.listener.*
import org.antagon.acore.util.ReferralManager
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Constructor

class Acore : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var referralManager: ReferralManager

    override fun onEnable() {
        // Initialize config
        configManager = ConfigManager.initialize(dataFolder, logger)

        // Initialize referral manager
        referralManager = ReferralManager(this)

        // Register commands
        registerCommands()

        // Register listeners
        registerListeners()

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
            server.pluginManager.registerEvents(StonecutterBlockProcessorListener(this), this)
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

        // Register CopperOxidationListener if enabled in config
        if (configManager.getBoolean("copperOxidation.enabled", true)) {
            server.pluginManager.registerEvents(CopperOxidationListener(this, configManager), this)
            logger.info("Copper Oxidation acceleration feature enabled")
        }
    }

    private fun registerCommands() {
        // Register showinfo command using Paper API
        try {
            val commandMap = server.commandMap
            var command = commandMap.getCommand("showinfo")
            if (command == null) {
                // Create command if it doesn't exist using reflection
                val constructor: Constructor<PluginCommand> =
                    PluginCommand::class.java.getDeclaredConstructor(String::class.java, org.bukkit.plugin.Plugin::class.java)
                constructor.isAccessible = true
                command = constructor.newInstance("showinfo", this)
                command.description = "Переключить отображение боссбара"
                command.usage = "/showinfo"
                (command as PluginCommand).setExecutor(ShowInfoCommand())
                commandMap.register("acore", command)
            } else {
                (command as PluginCommand).setExecutor(ShowInfoCommand())
            }
            logger.info("ShowInfo command registered")
        } catch (e: Exception) {
            logger.warning("Failed to register showinfo command: " + e.message)
        }

        // Register link command
        try {
            val commandMap = server.commandMap
            var command = commandMap.getCommand("link")
            if (command == null) {
                // Create command if it doesn't exist using reflection
                val constructor: Constructor<PluginCommand> =
                    PluginCommand::class.java.getDeclaredConstructor(String::class.java, org.bukkit.plugin.Plugin::class.java)
                constructor.isAccessible = true
                command = constructor.newInstance("link", this)
                command.description = "Invite a player to become your referral"
                command.usage = "/link <player>"
                (command as PluginCommand).setExecutor(LinkCommand(this, referralManager))
                commandMap.register("acore", command)
            } else {
                (command as PluginCommand).setExecutor(LinkCommand(this, referralManager))
            }
            logger.info("Link command registered")
        } catch (e: Exception) {
            logger.warning("Failed to register link command: " + e.message)
        }
    }

    override fun onDisable() {
        // Plugin shutdown logic
        logger.info("Acore plugin has been disabled")
    }
}