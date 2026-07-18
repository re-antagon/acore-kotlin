package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack

class ItemFrameListener(private val config: ConfigManager) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (!config.getBoolean("invisibleItemFrames.enabled", true)) {
            return
        }

        if (event.rightClicked.type != EntityType.ITEM_FRAME &&
            event.rightClicked.type != EntityType.GLOW_ITEM_FRAME) {
            return
        }

        val player = event.player
        val itemInHand = player.inventory.itemInMainHand

        if (itemInHand.type != Material.SHEARS) {
            return
        }

        val frame = event.rightClicked as ItemFrame

        if (!frame.item.type.isAir) {
            if (frame.isVisible == false && config.getBoolean("invisibleItemFrames.toggleable", true)) {
                frame.isVisible = true
            } else {
                frame.isVisible = false
            }

            event.isCancelled = true
        }
    }
}