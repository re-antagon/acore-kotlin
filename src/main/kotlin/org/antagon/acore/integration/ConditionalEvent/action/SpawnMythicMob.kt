package org.antagon.acore.integration.ConditionalEvent.action

import ce.ajneb97.api.ConditionalEventsAction
import io.lumine.mythic.bukkit.MythicBukkit
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

class SpawnMythicMob : ConditionalEventsAction() {

    init {
        super("spawn_mythic_mob")
    }

    override fun execute(player: Player, s: String, event: Event) {
        // Format: spawn_mythic_mob: <mythic_mob_type>;<world>;<x>;<y>;<z>;<amount>
        val sep = s.split(";")

        val mobType = sep[0]
        val location = Location(
            Bukkit.getWorld(sep[1]),
            sep[2].toDouble(),
            sep[3].toDouble(),
            sep[4].toDouble()
        )
        val amount = if (sep.size > 5) sep[5].toInt() else 1

        for (i in 0 until amount) {
            MythicBukkit.inst().mobManager.spawnMob(mobType, location)
        }
    }
}
