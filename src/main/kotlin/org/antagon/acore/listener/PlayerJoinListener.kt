package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.logging.Logger

class PlayerJoinListener : Listener {
    private val logger = Logger.getLogger(PlayerJoinListener::class.java.name)
    private val firstJoinItemEnabled: Boolean
    private val mythicItemName: String
    private val customModelData: Int
    private val dataFolder: File
    private val receivedPlayersFile: Path

    init {
        val config = ConfigManager.getInstance()

        firstJoinItemEnabled = config.getBoolean("firstJoinItem.enabled", true)
        mythicItemName = config.getString("firstJoinItem.mythicItemName", "menu_book")
        customModelData = config.getInt("firstJoinItem.customModelData", 1039)

        // Get data folder from the plugin
        dataFolder = Bukkit.getPluginManager().getPlugin("acore")?.dataFolder ?: throw IllegalStateException("Acore plugin not found")
        receivedPlayersFile = Paths.get(dataFolder.absolutePath, "first_join_players.yml")

        // Create the file if it doesn't exist
        try {
            if (!Files.exists(receivedPlayersFile)) {
                Files.createDirectories(receivedPlayersFile.parent)
                Files.createFile(receivedPlayersFile)
            }
        } catch (e: IOException) {
            logger.severe("Failed to create first join players file: " + e.message)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (!firstJoinItemEnabled) return

        val playerUUID = player.uniqueId

        // Check if player already received the item
        if (hasReceivedItem(playerUUID)) {
            return // Player already received the item
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

            // Mark as received
            markAsReceived(playerUUID)

            logger.info("Gave first join item '$mythicItemName' with model data $customModelData to ${player.name}")

        } catch (e: Exception) {
            logger.severe("Error giving first join item to ${player.name}: " + e.message)
            e.printStackTrace()
        }
    }

    private fun hasReceivedItem(playerUUID: UUID): Boolean {
        try {
            val lines = Files.readAllLines(receivedPlayersFile)
            return lines.contains(playerUUID.toString())
        } catch (e: IOException) {
            logger.warning("Failed to read first join players file: " + e.message)
            return false
        }
    }

    private fun markAsReceived(playerUUID: UUID) {
        try {
            Files.writeString(receivedPlayersFile, playerUUID.toString() + System.lineSeparator(),
                StandardOpenOption.APPEND)
        } catch (e: IOException) {
            logger.severe("Failed to write to first join players file: " + e.message)
        }
    }
}