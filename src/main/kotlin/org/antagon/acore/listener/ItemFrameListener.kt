package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.module.AcoreModule
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

class ItemFrameListener(
    private val plugin: Plugin = Acore.instance,
    private val config: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Invisible Item Frames"

    override fun shouldEnable(): Boolean {
        return config.getBoolean("invisibleItemFrames.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

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