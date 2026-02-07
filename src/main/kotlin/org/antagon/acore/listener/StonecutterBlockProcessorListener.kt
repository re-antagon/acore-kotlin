package org.antagon.acore.listener

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.HashSet

class StonecutterBlockProcessorListener(private val plugin: JavaPlugin) : Listener {

    private val trackedItems: MutableSet<Item> = HashSet()

    init {
        // Start scheduled task to check all dropped sandstone every 5 ticks
        startProcessingTask()
    }

    private fun startProcessingTask() {
        object : BukkitRunnable() {
            override fun run() {
                processingTask()
            }
        }.runTaskTimer(plugin, 0L, 5L) // Run every 5 ticks (0.25 seconds)
    }

    /**
     * Adds items to the tracking set.
     */
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val item = event.itemDrop

        if (item.itemStack.type == Material.SANDSTONE) {
            trackedItems.add(item)
        }
    }

    /**
     * Starts the single synchronous task that runs every 5 ticks.
     */
    private fun processingTask() {
        if (trackedItems.isEmpty()) {
            return
        }

        val iterator = trackedItems.iterator()

        while (iterator.hasNext()) {
            val item = iterator.next()

            // 1. Clean up invalid items (picked up, despawned, burned, etc.)
            if (!item.isValid) {
                iterator.remove()
                continue
            }

            // 2. Logic Check: Is it on a Stonecutter?
            // We check the block directly beneath the item entity
            val location = item.location
            val blockBelow = location.block.getRelative(BlockFace.DOWN)
            val block = location.block

            if (blockBelow.type == Material.STONECUTTER || block.type == Material.STONECUTTER) {
                // Replace sandstone with sand
                convertItem(item)
                // Remove from tracking set as it is now processed/deleted
                iterator.remove()
            }
        }
    }

    /**
     * Handles the specific game logic for converting the item.
     */
    private fun convertItem(item: Item) {
        val droppedBlockAmount = item.itemStack.amount
        val amountPerBlock = 3
        val totalSandToDrop = droppedBlockAmount * amountPerBlock

        // Remove the sandstone entity
        item.remove()

        // Drop the sand
        // We drop 'amountToDrop' individual items or a stack depending on preference.
        // Here we drop a single stack of 3 sand.
        if (totalSandToDrop > 0) {
            val sandStack = ItemStack(Material.SAND, totalSandToDrop)
            item.world.dropItem(item.location, sandStack)
        }
    }
}