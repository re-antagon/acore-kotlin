package org.antagon.acore.util

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

// Tracks player entity kills in specific areas
class EntityKillTracker private constructor() {
    // Map of position -> list of player kills (timestamp + player name)
    private val kills: MutableMap<BlockPos, MutableList<KillRecord>> = ConcurrentHashMap()

    companion object {
        private var instance: EntityKillTracker? = null

        fun getInstance(): EntityKillTracker {
            if (instance == null) {
                instance = EntityKillTracker()
            }
            return instance!!
        }
    }

    // Records a player killing an entity
    fun recordKill(player: Player, location: Location) {
        val key = BlockPos.from(location)
        val timestamp = System.currentTimeMillis()

        val locationKills = kills.computeIfAbsent(key) { mutableListOf() }

        // Add new kill record
        locationKills.add(KillRecord(player.name, timestamp))

        // Keep only last 10 kills per location to prevent memory leaks
        if (locationKills.size > 10) {
            locationKills.removeAt(0)
        }
    }

    // Gets players who killed entities in the specified radius within the time limit
    fun getPlayersWhoKilledEntities(center: Location, radius: Int, timeHours: Int = 12): List<String> {
        val centerKey = BlockPos.from(center)
        val players = mutableSetOf<String>()

        val cutoffTime = System.currentTimeMillis() - (timeHours * 60 * 60 * 1000L)

        // Check all locations within radius
        for ((killPos, locationKills) in kills) {
            if (centerKey.isWithinRadius(killPos, radius)) {
                for (kill in locationKills) {
                    // Only include kills within time limit
                    if (kill.timestamp >= cutoffTime) {
                        players.add(kill.playerName)
                    }
                }
            }
        }

        return players.toList()
    }

    // Represents a player kill record
    private data class KillRecord(
        val playerName: String,
        val timestamp: Long
    )

    // Cleans up old kill records to prevent memory leaks
    fun cleanupOldKills() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago

        kills.entries.removeIf { entry ->
            entry.value.removeIf { kill -> kill.timestamp < cutoffTime }
            entry.value.isEmpty()
        }
    }
}
