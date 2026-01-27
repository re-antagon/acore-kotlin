package org.antagon.acore.integration.ConditionalEvent.action

import ce.ajneb97.api.ConditionalEventsAction
import io.lumine.mythic.bukkit.MythicBukkit
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack

class DropMythicItem : ConditionalEventsAction() {

    init {
        super("drop_mythic_item")
    }

    override fun execute(player: Player, actionLine: String, event: Event) {
        // Format: drop_mythic_item:<item_id>;<amount>;<world>;<x>;<y>;<z>
        val args = actionLine.split(";")

        val itemID = args[0]
        val amount = args[1].toInt()
        val location = Location(
            Bukkit.getWorld(args[2]),
            args[3].toDouble(),
            args[4].toDouble(),
            args[5].toDouble()
        )

        MythicBukkit.inst().itemManager.getItem(itemID).ifPresent { mythicItem ->
            val itemStack = mythicItem.generateItemStack(amount) as ItemStack
            location.world?.dropItem(location, itemStack)
        }
    }
}
