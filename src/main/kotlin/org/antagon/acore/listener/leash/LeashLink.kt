package org.antagon.acore.listener.leash

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

// runtime state of one active leash connection
data class LeashLink(
    val holder: LeashHolder,
    val targetId: UUID,
    val fakeEntityId: Int = -1,
    val fakeEntityUuid: UUID = UUID.randomUUID(),
    val viewers: MutableSet<UUID> = linkedSetOf()
) {
    fun target(): Player? = Bukkit.getPlayer(targetId)
}
