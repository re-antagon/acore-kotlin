package org.antagon.acore.listener

import org.antagon.acore.util.EntityKillTracker
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

// Listens for player entity kills to track them for indicator potion feature
class EntityKillListener : Listener {
    private val tracker: EntityKillTracker

    init {
        tracker = EntityKillTracker.getInstance()
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        // Check if the killer is a player
        val killer = event.entity.killer ?: return
        
        // Get the location where the entity died
        val location = event.entity.location
        
        // Record the kill
        tracker.recordKill(killer, location)
    }
}
