package org.antagon.acore.feature.entity

import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.util.EntityKillTracker
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.plugin.Plugin

// Listens for player entity kills to track them for indicator potion feature
class EntityKillListener(
    private val plugin: Plugin = Acore.instance
) : AcoreModule, Listener {

    override val name: String = "Entity Kill Tracker"

    override fun shouldEnable(): Boolean = true

    override fun enable() {
        registerEvents(plugin)
    }
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
