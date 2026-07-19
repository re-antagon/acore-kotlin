package org.antagon.acore.listener

import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.BlockInteractionTracker.InteractionType
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent

// Listens for player interactions with blocks to track them for indicator potion feature
class BlockInteractionListener : Listener {
    private val tracker: BlockInteractionTracker

    init {
        tracker = BlockInteractionTracker.getInstance()
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val location = event.block.location
        // Record as BREAK interaction
        tracker.recordInteraction(player, location, InteractionType.BREAK)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val location = event.block.location
        // Record as PLACE interaction
        tracker.recordInteraction(player, location, InteractionType.PLACE)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // Only track interactions with blocks, not air
        if (event.clickedBlock == null) return

        val player = event.player
        val location = event.clickedBlock!!.location
        // Record as INTERACT interaction
        tracker.recordInteraction(player, location, InteractionType.INTERACT)
    }
}