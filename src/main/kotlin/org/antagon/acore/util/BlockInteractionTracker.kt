package org.antagon.acore.util

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BlockInteractionTracker private constructor() {
    private val interactions: MutableMap<Location, MutableList<PlayerInteraction>> = ConcurrentHashMap()

    companion object {
        private var instance: BlockInteractionTracker? = null

        fun getInstance(): BlockInteractionTracker {
            if (instance == null) {
                instance = BlockInteractionTracker()
            }
            return instance!!
        }
    }

    fun recordInteraction(player: Player, location: Location) {
        val interaction = PlayerInteraction(player.uniqueId, System.currentTimeMillis())
        interactions.computeIfAbsent(location) { mutableListOf() }.add(interaction)

        // Clean up old interactions (older than 5 minutes)
        cleanupOldInteractions()
    }

    fun getRecentInteractions(location: Location, timeWindowMs: Long): List<PlayerInteraction> {
        val locationInteractions = interactions[location] ?: return emptyList()
        val currentTime = System.currentTimeMillis()
        return locationInteractions.filter { currentTime - it.timestamp < timeWindowMs }
    }

    private fun cleanupOldInteractions() {
        val currentTime = System.currentTimeMillis()
        val fiveMinutesMs = 5 * 60 * 1000L

        interactions.entries.removeIf { entry ->
            entry.value.removeIf { currentTime - it.timestamp > fiveMinutesMs }
            entry.value.isEmpty()
        }
    }

    data class PlayerInteraction(val playerId: UUID, val timestamp: Long)
}