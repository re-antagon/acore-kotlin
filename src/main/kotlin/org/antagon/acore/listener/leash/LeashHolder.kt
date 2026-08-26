package org.antagon.acore.listener.leash

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import java.util.UUID

// abstraction over the leash holder
interface LeashHolder {
    val uniqueId: UUID

    fun resolveEntity(): LivingEntity?

    fun resolveLocation(): Location? = resolveEntity()?.location

    fun resolveEntityId(): Int? = resolveEntity()?.entityId

    fun displayName(): String = resolveEntity()?.name ?: uniqueId.toString()
}

data class PlayerLeashHolder(
    override val uniqueId: UUID
) : LeashHolder {
    override fun resolveEntity(): LivingEntity? = Bukkit.getPlayer(uniqueId)
}
