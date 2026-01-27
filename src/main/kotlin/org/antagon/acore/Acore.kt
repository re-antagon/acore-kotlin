package org.antagon.acore

import org.antagon.acore.commands.AntiSchvapchichiCommand
import org.antagon.acore.commands.LinkCommand
import org.antagon.acore.commands.SchvapchichiCommand
import org.antagon.acore.commands.ShowInfoCommand
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.listener.*
import org.antagon.acore.util.CurseManager
import org.antagon.acore.util.ReferralManager
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Constructor

class Acore : JavaPlugin() {
    private lateinit var configManager: ConfigManager
    private lateinit var curseManager: CurseManager
    private lateinit var referralManager: ReferralManager

    override fun onEnable() {
        // Initialize config
        configManager = ConfigManager.initialize(dataFolder, logger)

        // Initialize curse manager
        curseManager = CurseManager(this)

        // Initialize referral manager
        referralManager = ReferralManager(this)

        // Register commands
        registerCommands()

        // Register listeners
        registerListeners()

        logger.info("Acore plugin has been enabled successfully!")

        // !!! С этим полем плагин не заводится, хз почему, поэтому пока так !!!
        // потом почекаю
        //ConditionalEventsAPI.registerApiActions(this,new SpawnMythicMob(), new DropMythicItem());
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

        // Register FogPotionListener if enabled in config
        if (configManager.getBoolean("fogPotion.enabled", true)) {
            server.pluginManager.registerEvents(FogPotionListener(this, configManager), this)
            logger.info("Fog Potion feature enabled")
        }

        // Register BannerHeadListener if enabled in config
        if (configManager.getBoolean("bannerHead.enabled", true)) {
            server.pluginManager.registerEvents(BannerHeadListener(configManager), this)
            logger.info("Banner Head feature enabled")
        }

        // Register SchvapchichiListener if enabled in config
        if (configManager.getBoolean("schvapchichi.enabled", true)) {
            server.pluginManager.registerEvents(SchvapchichiListener(this, curseManager), this)
            logger.info("Schvapchichi feature enabled")
            logger.info("Cursed players loaded: " + curseManager.cursedPlayers.size)
        }

        // Register PlayerMoveListener
        server.pluginManager.registerEvents(PlayerMoveListener(), this)

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

        // Register schvapchichi command
        try {
            val commandMap = server.commandMap
            var command = commandMap.getCommand("schvapchichi")
            if (command == null) {
                // Create command if it doesn't exist using reflection
                val constructor: Constructor<PluginCommand> =
                    PluginCommand::class.java.getDeclaredConstructor(String::class.java, org.bukkit.plugin.Plugin::class.java)
                constructor.isAccessible = true
                command = constructor.newInstance("schvapchichi", this)
                command.description = "Проклясть игрока Швапчичи"
                command.usage = "/schvapchichi"
                (command as PluginCommand).setExecutor(SchvapchichiCommand(this, curseManager))
                commandMap.register("acore", command)
            } else {
                (command as PluginCommand).setExecutor(SchvapchichiCommand(this, curseManager))
            }
            logger.info("Schvapchichi command registered")
        } catch (e: Exception) {
            logger.warning("Failed to register schvapchichi command: " + e.message)
        }

        // Register anti_schvapchichi command
        try {
            val commandMap = server.commandMap
            var command = commandMap.getCommand("anti_schvapchichi")
            if (command == null) {
                // Create command if it doesn't exist using reflection
                val constructor: Constructor<PluginCommand> =
                    PluginCommand::class.java.getDeclaredConstructor(String::class.java, org.bukkit.plugin.Plugin::class.java)
                constructor.isAccessible = true
                command = constructor.newInstance("anti_schvapchichi", this)
                command.description = "Избавиться от проклятья Швапчичи"
                command.usage = "/anti_schvapchichi"
                (command as PluginCommand).setExecutor(AntiSchvapchichiCommand(this, curseManager))
                commandMap.register("acore", command)
            } else {
                (command as PluginCommand).setExecutor(AntiSchvapchichiCommand(this, curseManager))
            }
            logger.info("AntiSchvapchichi command registered")
        } catch (e: Exception) {
            logger.warning("Failed to register anti_schvapchichi command: " + e.message)
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

        // !!! Жиза !!!
        //ConditionalEventsAPI.unregisterApiActions(this);
    }
}