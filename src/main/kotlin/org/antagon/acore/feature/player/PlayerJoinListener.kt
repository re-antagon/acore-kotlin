package org.antagon.acore.feature.player

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import java.util.logging.Logger

class PlayerJoinListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "First Join Item"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("firstJoinItem.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val logger = Logger.getLogger(PlayerJoinListener::class.java.name)
    private val mythicItemName: String
    private val customModelData: Int

    init {
        mythicItemName = configManager.getString("firstJoinItem.mythicItemName", "menu_book")
        customModelData = configManager.getInt("firstJoinItem.customModelData", 1039)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Check if player has played before using Bukkit/Paper API
        if (player.hasPlayedBefore()) {
            return
        }

        val item = org.antagon.acore.util.MythicMobsHelper.getMythicItem(mythicItemName)
        if (item == null) {
            logger.warning("MythicMobs item '$mythicItemName' not found, cannot give first join item to ${player.name}")
            return
        }

        try {
            // Set custom model data
            val modelData = CustomModelData.customModelData()
                .addFloat(customModelData.toFloat())
                .build()
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, modelData)

            // Give item to player
            player.inventory.addItem(item)

            logger.info("Gave first join item '$mythicItemName' with model data $customModelData to ${player.name}")

        } catch (e: Exception) {
            logger.severe("Error giving first join item to ${player.name}: " + e.message)
            e.printStackTrace()
        }
    }
}