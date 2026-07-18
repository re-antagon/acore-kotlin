package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta

class BannerHeadListener(private val config: ConfigManager) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!config.getBoolean("bannerHead.enabled", true)) {
            return
        }

        val player = event.whoClicked as? Player ?: return
        val clickedInventory = event.clickedInventory ?: return
        val clickedItem = event.currentItem
        val cursorItem = event.cursor

        if (event.slotType == InventoryType.SlotType.ARMOR && event.slot == 39) {
            if (clickedInventory !is org.bukkit.inventory.PlayerInventory) {
                return
            }

            if (event.click == ClickType.NUMBER_KEY) {
                val hotbarSlot = event.hotbarButton
                if (hotbarSlot in 0..8) {
                    val hotbarItem = player.inventory.getItem(hotbarSlot)
                    if (isBanner(hotbarItem) || isBanner(clickedItem)) {
                        player.inventory.setItem(hotbarSlot, if (clickedItem != null && clickedItem.type != Material.AIR) clickedItem.clone() else ItemStack(Material.AIR))
                        player.inventory.helmet = if (hotbarItem != null && hotbarItem.type != Material.AIR) hotbarItem.clone() else ItemStack(Material.AIR)
                        event.isCancelled = true
                        player.updateInventory()
                    }
                }
                return
            }

            if (isBanner(clickedItem) && event.isShiftClick) {
                val toMove = clickedItem!!.clone()
                player.inventory.helmet = ItemStack(Material.AIR)
                event.currentItem = ItemStack(Material.AIR)
                val leftover = player.inventory.addItem(toMove)
                for (item in leftover.values) {
                    player.world.dropItemNaturally(player.location, item)
                }
                event.isCancelled = true
                player.updateInventory()
                return
            }

            if (isBanner(cursorItem)) {
                val cursor = cursorItem
                val current = clickedItem

                if (cursor.amount == 1) {
                    val newHelmet = cursor.clone()
                    player.inventory.helmet = newHelmet
                    event.currentItem = newHelmet
                    if (current != null && current.type != Material.AIR) {
                        event.setCursor(current.clone())
                    } else {
                        event.setCursor(ItemStack(Material.AIR))
                    }
                } else {
                    val singleBanner = cursor.clone()
                    singleBanner.amount = 1
                    player.inventory.helmet = singleBanner
                    event.currentItem = singleBanner

                    val remainingCursor = cursor.clone()
                    remainingCursor.amount = cursor.amount - 1
                    event.setCursor(remainingCursor)

                    if (current != null && current.type != Material.AIR) {
                        val leftover = player.inventory.addItem(current.clone())
                        for (item in leftover.values) {
                            player.world.dropItemNaturally(player.location, item)
                        }
                    }
                }
                event.isCancelled = true
                player.updateInventory()
                return
            }

            if (isBanner(clickedItem)) {
                val current = clickedItem!!
                if (cursorItem.type == Material.AIR) {
                    event.setCursor(current.clone())
                    player.inventory.helmet = ItemStack(Material.AIR)
                    event.currentItem = ItemStack(Material.AIR)
                    event.isCancelled = true
                    player.updateInventory()
                    return
                } else {
                    if (cursorItem.amount == 1) {
                        val newHelmet = cursorItem.clone()
                        player.inventory.helmet = newHelmet
                        event.currentItem = newHelmet
                        event.setCursor(current.clone())
                    } else {
                        val newHelmet = cursorItem.clone()
                        newHelmet.amount = 1
                        player.inventory.helmet = newHelmet
                        event.currentItem = newHelmet

                        val remainingCursor = cursorItem.clone()
                        remainingCursor.amount = cursorItem.amount - 1
                        event.setCursor(remainingCursor)

                        val leftover = player.inventory.addItem(current.clone())
                        for (item in leftover.values) {
                            player.world.dropItemNaturally(player.location, item)
                        }
                    }
                    event.isCancelled = true
                    player.updateInventory()
                    return
                }
            }
            return
        }

        if (event.isShiftClick && isBanner(clickedItem) && clickedInventory is org.bukkit.inventory.PlayerInventory && event.slot != 39) {
            val topType = event.view.topInventory.type
            val isPlayerScreen = topType == InventoryType.CRAFTING ||
                                 topType == InventoryType.PLAYER ||
                                 topType == InventoryType.CREATIVE ||
                                 topType == InventoryType.WORKBENCH

            if (isPlayerScreen) {
                val currentHelmet = player.inventory.helmet
                if (currentHelmet == null || currentHelmet.type == Material.AIR) {
                    val bannerStack = clickedItem!!.clone()
                    if (bannerStack.amount == 1) {
                        player.inventory.helmet = bannerStack
                        event.currentItem = ItemStack(Material.AIR)
                    } else {
                        val singleBanner = bannerStack.clone()
                        singleBanner.amount = 1
                        player.inventory.helmet = singleBanner

                        val remaining = bannerStack.clone()
                        remaining.amount = bannerStack.amount - 1
                        event.currentItem = remaining
                    }
                    event.isCancelled = true
                    player.updateInventory()
                }
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