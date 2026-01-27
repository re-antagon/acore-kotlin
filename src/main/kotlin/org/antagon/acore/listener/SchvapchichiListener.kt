package org.antagon.acore.listener

import org.antagon.acore.util.CurseManager
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import kotlin.collections.HashMap

class SchvapchichiListener(private val plugin: JavaPlugin, private val curseManager: CurseManager) : Listener {
    private val random: Random = Random()
    private val lastBeehiveCheck: MutableMap<UUID, Long> = HashMap()

    // Handle player join event - check for cursed players and potentially steal items
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val playerId = player.uniqueId

        // Check if player is cursed (from file or metadata)
        if (player.hasMetadata("schvapchichi_cursed") || curseManager.isPlayerCursed(playerId)) {
            plugin.logger.info("Player ${player.name} is cursed, checking for item steal...")

            if (shouldStealItem()) {
                plugin.logger.info("Stealing item from ${player.name}")
                stealRandomItem(player)
            } else {
                plugin.logger.info("No item stolen from ${player.name} this time")
            }
        }
    }

    // Steal a random item from player's inventory
    private fun stealRandomItem(player: Player) {
        val contents = player.inventory.contents

        // Find non-empty slots
        val items = mutableListOf<ItemStack>()
        for (item in contents) {
            if (item != null && item.type != Material.AIR) {
                items.add(item)
            }
        }

        if (items.isNotEmpty()) {
            // Select random item
            val stolenItem = items[random.nextInt(items.size)]

            plugin.logger.info("Selected item to steal: ${stolenItem.type} x${stolenItem.amount}")

            // Create a copy of the item to remove
            val itemToRemove = stolenItem.clone()

            // Remove one from the stack
            if (stolenItem.amount > 1) {
                stolenItem.amount = stolenItem.amount - 1
                itemToRemove.amount = 1
            } else {
                // Remove the entire item from inventory
                player.inventory.removeItem(stolenItem)
            }

            // Send message to player
            val message = plugin.config.getString("schvapchichi.curse.message", "Швапчичи забирает это...")
            player.sendMessage("§c$message")

            plugin.logger.info("Successfully stole item from ${player.name}")
        } else {
            plugin.logger.info("No items to steal from ${player.name}")
        }
    }

    // Determine if item should be stolen based on configured chance
    private fun shouldStealItem(): Boolean {
        val stealChance = plugin.config.getDouble("schvapchichi.curse.stealChance", 0.05)
        val roll = random.nextDouble()
        val shouldSteal = roll < stealChance
        plugin.logger.info("Steal chance: $stealChance, rolled: $roll, should steal: $shouldSteal")
        return shouldSteal
    }
}
