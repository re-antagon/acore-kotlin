package org.antagon.acore.util

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player interactions with blocks in specific areas
 */
class BlockInteractionTracker private constructor() {
    // Map of location -> list of player interactions (timestamp + player name)
    private val interactions: MutableMap<Location, MutableList<PlayerInteraction>> = ConcurrentHashMap()

    // Map of location -> last use timestamp for cooldowns
    private val cooldowns: MutableMap<Location, Long> = ConcurrentHashMap()

    // Map of player UUID -> last use timestamp for player cooldowns
    private val playerCooldowns: MutableMap<String, Long> = ConcurrentHashMap()

    companion object {
        private var instance: BlockInteractionTracker? = null

        fun getInstance(): BlockInteractionTracker {
            if (instance == null) {
                instance = BlockInteractionTracker()
            }
            return instance!!
        }
    }

    /**
     * Records a player interaction with a block
     */
    fun recordInteraction(player: Player, location: Location) {
        val key = roundLocation(location)

        val locationInteractions = interactions.computeIfAbsent(key) { mutableListOf() }

        // Add new interaction
        locationInteractions.add(PlayerInteraction(player.name, System.currentTimeMillis()))

        // Keep only last 10 interactions per location to prevent memory leaks
        if (locationInteractions.size > 10) {
            locationInteractions.removeAt(0)
        }
    }

    /**
     * Gets the last 3 players who interacted with blocks in the specified radius
     */
    fun getLastPlayersInRadius(center: Location, radius: Int): List<String> {
        val centerKey = roundLocation(center)
        val players = mutableListOf<String>()

        val currentTime = System.currentTimeMillis()

        // Check all locations within radius
        for ((interactionLocation, locationInteractions) in interactions) {
            if (isWithinRadius(centerKey, interactionLocation, radius)) {
                for (interaction in locationInteractions) {
                    if (!players.contains(interaction.playerName)) {
                        players.add(interaction.playerName)
                        if (players.size >= 3) {
                            return players
                        }
                    }
                }
            }
        }

        return players
    }

    /**
     * Checks if a location is on cooldown
     */
    fun isOnCooldown(location: Location, cooldownSeconds: Int): Boolean {
        val key = roundLocation(location)
        val lastUse = cooldowns[key]

        if (lastUse == null) {
            return false
        }

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        return timeSinceLastUse < cooldownSeconds
    }

    /**
     * Gets remaining cooldown time in seconds for a location
     */
    fun getRemainingCooldown(location: Location, cooldownSeconds: Int): Int {
        val key = roundLocation(location)
        val lastUse = cooldowns[key]

        if (lastUse == null) {
            return 0
        }

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        val remaining = cooldownSeconds - timeSinceLastUse

        return maxOf(0, remaining.toInt())
    }

    /**
     * Sets cooldown for a location
     */
    fun setCooldown(location: Location) {
        val key = roundLocation(location)
        cooldowns[key] = System.currentTimeMillis()
    }

    /**
     * Force removes cooldown for a location (for testing purposes)
     */
    fun removeCooldown(location: Location) {
        val key = roundLocation(location)
        cooldowns.remove(key)
    }

    /**
     * Gets all cooldown locations (for debugging)
     */
    fun getAllCooldowns(): Map<Location, Long> {
        return ConcurrentHashMap(cooldowns)
    }

    /**
     * Checks if a player is on cooldown
     */
    fun isPlayerOnCooldown(player: Player, cooldownSeconds: Int): Boolean {
        val playerUUID = player.uniqueId.toString()
        val lastUse = playerCooldowns[playerUUID]

        if (lastUse == null) {
            return false
        }

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        return timeSinceLastUse < cooldownSeconds
    }

    /**
     * Gets remaining cooldown time in seconds for a player
     */
    fun getPlayerRemainingCooldown(player: Player, cooldownSeconds: Int): Int {
        val playerUUID = player.uniqueId.toString()
        val lastUse = playerCooldowns[playerUUID]

        if (lastUse == null) {
            return 0
        }

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        val remaining = cooldownSeconds - timeSinceLastUse

        return maxOf(0, remaining.toInt())
    }

    /**
     * Sets cooldown for a player
     */
    fun setPlayerCooldown(player: Player) {
        val playerUUID = player.uniqueId.toString()
        playerCooldowns[playerUUID] = System.currentTimeMillis()
    }

    /**
     * Gets all player cooldowns (for debugging)
     */
    fun getAllPlayerCooldowns(): Map<String, Long> {
        return ConcurrentHashMap(playerCooldowns)
    }

    /**
     * Rounds location to block coordinates for consistent tracking
     */
    private fun roundLocation(location: Location): Location {
        return Location(
            location.world,
            location.blockX.toDouble(),
            location.blockY.toDouble(),
            location.blockZ.toDouble()
        )
    }

    /**
     * Gets the exact location key for debugging purposes
     */
    fun getLocationKey(location: Location): Location {
        return roundLocation(location)
    }

    /**
     * Checks if two locations are within specified radius
     */
    private fun isWithinRadius(center: Location, location: Location, radius: Int): Boolean {
        if (center.world != location.world) {
            return false
        }

        val distance = center.distance(location)
        return distance <= radius
    }

    /**
     * Checks if two locations are the same (for exact location matching)
     */
    private fun isSameLocation(loc1: Location, loc2: Location): Boolean {
        if (loc1.world != loc2.world) {
            return false
        }

        return loc1.blockX == loc2.blockX &&
               loc1.blockY == loc2.blockY &&
               loc1.blockZ == loc2.blockZ
    }

    /**
     * Represents a player interaction with a block
     */
    private data class PlayerInteraction(
        val playerName: String,
        val timestamp: Long
    )

    /**
     * Cleans up old interactions to prevent memory leaks
     */
    fun cleanupOldInteractions() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago

        interactions.entries.removeIf { entry ->
            entry.value.removeIf { interaction -> interaction.timestamp < cutoffTime }
            entry.value.isEmpty()
        }

        // Clean up old cooldowns (older than 1 hour)
        val cooldownCutoff = System.currentTimeMillis() - (60 * 60 * 1000)
        cooldowns.entries.removeIf { entry -> entry.value < cooldownCutoff }

        // Clean up old player cooldowns (older than 1 hour)
        playerCooldowns.entries.removeIf { entry -> entry.value < cooldownCutoff }
    }
}