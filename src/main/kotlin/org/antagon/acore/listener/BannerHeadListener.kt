package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.BannerMeta

class BannerHeadListener(private val config: ConfigManager) : Listener {

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!config.getBoolean("bannerHead.enabled", true)) {
            return
        }

        if (event.inventory !is PlayerInventory) {
            return
        }

        val player = event.whoClicked as Player
        val clickedItem = event.currentItem
        val cursorItem = event.cursor

        if (isBanner(clickedItem) || isBanner(cursorItem)) {
            val banner = if (isBanner(clickedItem)) clickedItem else cursorItem

            if (event.slot == 39) { // Head slot
                player.inventory.helmet = banner?.clone()

                if (isBanner(clickedItem)) {
                    event.currentItem = ItemStack(Material.AIR)
                } else {
                    event.cursor = null
                }

                event.isCancelled = true
                player.updateInventory()
            }
        }
    }

    private fun isBanner(item: ItemStack?): Boolean {
        if (item == null || item.type == Material.AIR) {
            return false
        }

        val type = item.type
        return type == Material.WHITE_BANNER ||
               type == Material.ORANGE_BANNER ||
               type == Material.MAGENTA_BANNER ||
               type == Material.LIGHT_BLUE_BANNER ||
               type == Material.YELLOW_BANNER ||
               type == Material.LIME_BANNER ||
               type == Material.PINK_BANNER ||
               type == Material.GRAY_BANNER ||
               type == Material.LIGHT_GRAY_BANNER ||
               type == Material.CYAN_BANNER ||
               type == Material.PURPLE_BANNER ||
               type == Material.BLUE_BANNER ||
               type == Material.BROWN_BANNER ||
               type == Material.GREEN_BANNER ||
               type == Material.RED_BANNER ||
               type == Material.BLACK_BANNER ||
               (item.hasItemMeta() && item.itemMeta is BannerMeta)
    }
}