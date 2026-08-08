package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.InteractionType
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin

// Listens for player interactions with blocks to track them for indicator potion feature
class BlockInteractionListener(
    private val plugin: Plugin = Acore.instance
) : AcoreModule, Listener {

    override val name: String = "Block Interaction Tracker"

    override fun shouldEnable(): Boolean = true

    override fun enable() {
        registerEvents(plugin)
    }
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