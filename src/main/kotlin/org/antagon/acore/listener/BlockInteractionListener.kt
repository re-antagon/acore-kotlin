package org.antagon.acore.listener

import org.antagon.acore.util.BlockInteractionTracker
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent

// Listens for player interactions with blocks to track them for fog potion feature
class BlockInteractionListener : Listener {
    private val tracker: BlockInteractionTracker

    init {
        tracker = BlockInteractionTracker.getInstance()
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player ?: return
        val location = event.block.location
        tracker.recordInteraction(player, location)
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player ?: return
        val location = event.block.location
        tracker.recordInteraction(player, location)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // Only track interactions with blocks, not air
        if (event.clickedBlock == null) return

        val player = event.player ?: return
        val location = event.clickedBlock!!.location
        tracker.recordInteraction(player, location)
    }
}