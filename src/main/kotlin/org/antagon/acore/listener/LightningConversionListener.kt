package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.weather.LightningStrikeEvent

class LightningConversionListener(private val config: ConfigManager) : Listener {
    private val conversions = mutableMapOf<Material, Material>()

    init {
        config.getSection("lightningConversion.block-types")?.getKeys(false)?.forEach { key ->
            val from = Material.matchMaterial(key)
            val to = Material.matchMaterial(config.getString("lightningConversion.block-types.$key", ""))
            if (from != null && to != null) conversions[from] = to
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onLightning(event: LightningStrikeEvent) {
        if (!config.getBoolean("lightningConversion.enabled", true)) return
        val b = event.lightning.location.block
        for (dx in -1..1) for (dz in -1..1) {
            val target = b.world.getBlockAt(b.x + dx, b.y - 1, b.z + dz)
            conversions[target.type]?.let { target.type = it }
        }
    }
}
