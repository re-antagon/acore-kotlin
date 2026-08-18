package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.Tag
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin

class SoulSoilTillListener(
    private val plugin: Plugin = Acore.instance,
    private val config: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Soul Soil Till"

    override fun shouldEnable(): Boolean {
        return config.getBoolean("soulSoilTill.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return
        if (block.type != Material.SOUL_SOIL) return

        val hand = event.hand ?: EquipmentSlot.HAND
        val player = event.player
        val item = event.item

        val requireHoe = config.getBoolean("soulSoilTill.requireHoe", true)

        if (requireHoe) {
            if (item == null || !Tag.ITEMS_HOES.isTagged(item.type)) {
                return
            }
        }

        // Cancel event to prevent placing blocks or default interact behavior
        event.isCancelled = true

        // Replace SOUL_SOIL block with SOUL_SAND
        block.type = Material.SOUL_SAND

        // Play hoe till sound at block position
        block.world.playSound(
            block.location.add(0.5, 0.5, 0.5),
            Sound.ITEM_HOE_TILL,
            SoundCategory.BLOCKS,
            1.0f,
            1.0f
        )

        // Send hand swing packet/animation
        if (hand == EquipmentSlot.OFF_HAND) {
            player.swingOffHand()
        } else {
            player.swingMainHand()
        }

        // Damage the hoe tool if player is not in CREATIVE mode
        if (item != null && Tag.ITEMS_HOES.isTagged(item.type) && player.gameMode != GameMode.CREATIVE) {
            item.damage(1, player)
        }
    }
}
