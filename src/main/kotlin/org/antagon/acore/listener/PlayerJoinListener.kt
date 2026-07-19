package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.logging.Logger

class PlayerJoinListener : Listener {
    private val logger = Logger.getLogger(PlayerJoinListener::class.java.name)
    private val firstJoinItemEnabled: Boolean
    private val mythicItemName: String
    private val customModelData: Int

    init {
        val config = ConfigManager.getInstance()

        firstJoinItemEnabled = config.getBoolean("firstJoinItem.enabled", true)
        mythicItemName = config.getString("firstJoinItem.mythicItemName", "menu_book")
        customModelData = config.getInt("firstJoinItem.customModelData", 1039)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (!firstJoinItemEnabled) return

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
            val meta = item.itemMeta
            if (meta != null) {
                meta.setCustomModelData(customModelData)
                item.itemMeta = meta
            }

            // Give item to player
            player.inventory.addItem(item)

            logger.info("Gave first join item '$mythicItemName' with model data $customModelData to ${player.name}")

        } catch (e: Exception) {
            logger.severe("Error giving first join item to ${player.name}: " + e.message)
            e.printStackTrace()
        }
    }
}