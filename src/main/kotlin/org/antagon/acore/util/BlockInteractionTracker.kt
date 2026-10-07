package org.antagon.acore.util

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

enum class InteractionType {
    PLACE,    // Block placed
    BREAK,    // Block broken
    INTERACT  // Player interacted with block (right-click)
}

// Tracks player interactions with blocks in specific areas
class BlockInteractionTracker private constructor() {
    // Map of position -> list of player interactions (timestamp + player name) - ALL interactions
    private val interactions: MutableMap<BlockPos, MutableList<PlayerInteraction>> = ConcurrentHashMap()
    
    // Separate maps for different interaction types
    private val blockPlaces: MutableMap<BlockPos, MutableList<PlayerInteraction>> = ConcurrentHashMap()
    private val blockBreaks: MutableMap<BlockPos, MutableList<PlayerInteraction>> = ConcurrentHashMap()
    private val blockInteracts: MutableMap<BlockPos, MutableList<PlayerInteraction>> = ConcurrentHashMap()

    // Map of position -> last use timestamp for cooldowns
    private val cooldowns: MutableMap<BlockPos, Long> = ConcurrentHashMap()

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

    // Records a player interaction with a block
    fun recordInteraction(player: Player, location: Location, type: InteractionType) {
        val key = BlockPos.from(location)
        val timestamp = System.currentTimeMillis()
        
        // Record in general interactions map (for backward compatibility)
        val locationInteractions = interactions.computeIfAbsent(key) { mutableListOf() }
        locationInteractions.add(PlayerInteraction(player.name, timestamp))
        if (locationInteractions.size > 10) {
            locationInteractions.removeAt(0)
        }
        
        // Record in specific type map
        val targetMap = when (type) {
            InteractionType.PLACE -> blockPlaces
            InteractionType.BREAK -> blockBreaks
            InteractionType.INTERACT -> blockInteracts
        }
        val typeList = targetMap.computeIfAbsent(key) { mutableListOf() }
        typeList.add(PlayerInteraction(player.name, timestamp))
        if (typeList.size > 10) {
            typeList.removeAt(0)
        }
    }

    // Records a player interaction with a block (legacy method for backward compatibility)
    fun recordInteraction(player: Player, location: Location) {
        recordInteraction(player, location, InteractionType.INTERACT)
    }

    // Gets players who placed blocks in the specified radius within the time limit
    fun getPlayersWhoPlacedBlocks(center: Location, radius: Int, timeHours: Int = 12): List<String> {
        return getPlayersInRadius(blockPlaces, center, radius, timeHours)
    }

    // Gets players who broke blocks in the specified radius within the time limit
    fun getPlayersWhoBrokeBlocks(center: Location, radius: Int, timeHours: Int = 12): List<String> {
        return getPlayersInRadius(blockBreaks, center, radius, timeHours)
    }

    // Gets players who interacted with blocks in the specified radius within the time limit
    fun getPlayersWhoInteracted(center: Location, radius: Int, timeHours: Int = 12): List<String> {
        return getPlayersInRadius(blockInteracts, center, radius, timeHours)
    }

    // Gets the last 3 players who interacted with blocks in the specified radius (legacy method)
    fun getLastPlayersInRadius(center: Location, radius: Int): List<String> {
        return getPlayersInRadius(interactions, center, radius, 24)
    }

    // Helper method to get players from a specific interaction map within radius and time
    private fun getPlayersInRadius(
        interactionMap: Map<BlockPos, MutableList<PlayerInteraction>>,
        center: Location,
        radius: Int,
        timeHours: Int
    ): List<String> {
        val centerKey = BlockPos.from(center)
        val players = mutableSetOf<String>()

        val cutoffTime = System.currentTimeMillis() - (timeHours * 60 * 60 * 1000L)

        // Check all locations within radius
        for ((pos, locationInteractions) in interactionMap) {
            if (centerKey.isWithinRadius(pos, radius)) {
                for (interaction in locationInteractions) {
                    // Only include interactions within time limit
                    if (interaction.timestamp >= cutoffTime) {
                        players.add(interaction.playerName)
                    }
                }
            }
        }

        return players.toList()
    }

    // Checks if a location is on cooldown
    fun isOnCooldown(location: Location, cooldownSeconds: Int): Boolean {
        val key = BlockPos.from(location)
        val lastUse = cooldowns[key] ?: return false

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        return timeSinceLastUse < cooldownSeconds
    }

    // Gets remaining cooldown time in seconds for a location
    fun getRemainingCooldown(location: Location, cooldownSeconds: Int): Int {
        val key = BlockPos.from(location)
        val lastUse = cooldowns[key] ?: return 0

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        val remaining = cooldownSeconds - timeSinceLastUse

        return maxOf(0, remaining.toInt())
    }

    // Sets cooldown for a location
    fun setCooldown(location: Location) {
        val key = BlockPos.from(location)
        cooldowns[key] = System.currentTimeMillis()
    }

    // Force removes cooldown for a location (for testing purposes)
    fun removeCooldown(location: Location) {
        val key = BlockPos.from(location)
        cooldowns.remove(key)
    }

    // Gets all cooldown locations (for debugging)
    fun getAllCooldowns(): Map<BlockPos, Long> {
        return ConcurrentHashMap(cooldowns)
    }

    // Checks if a player is on cooldown
    fun isPlayerOnCooldown(player: Player, cooldownSeconds: Int): Boolean {
        val playerUUID = player.uniqueId.toString()
        val lastUse = playerCooldowns[playerUUID] ?: return false

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        return timeSinceLastUse < cooldownSeconds
    }

    // Gets remaining cooldown time in seconds for a player
    fun getPlayerRemainingCooldown(player: Player, cooldownSeconds: Int): Int {
        val playerUUID = player.uniqueId.toString()
        val lastUse = playerCooldowns[playerUUID] ?: return 0

        val timeSinceLastUse = (System.currentTimeMillis() - lastUse) / 1000
        val remaining = cooldownSeconds - timeSinceLastUse

        return maxOf(0, remaining.toInt())
    }

    // Sets cooldown for a player
    fun setPlayerCooldown(player: Player) {
        val playerUUID = player.uniqueId.toString()
        playerCooldowns[playerUUID] = System.currentTimeMillis()
    }

    // Gets all player cooldowns (for debugging)
    fun getAllPlayerCooldowns(): Map<String, Long> {
        return ConcurrentHashMap(playerCooldowns)
    }

    // Gets the exact position key for debugging purposes
    fun getLocationKey(location: Location): BlockPos {
        return BlockPos.from(location)
    }

    // Represents a player interaction with a block
    private data class PlayerInteraction(
        val playerName: String,
        val timestamp: Long
    )

    // Cleans up old interactions to prevent memory leaks
    fun cleanupOldInteractions() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago

        // Clean all interaction maps
        listOf(interactions, blockPlaces, blockBreaks, blockInteracts).forEach { map ->
            map.entries.removeIf { entry ->
                entry.value.removeIf { interaction -> interaction.timestamp < cutoffTime }
                entry.value.isEmpty()
            }
        }

        // Clean up old cooldowns (older than 1 hour)
        val cooldownCutoff = System.currentTimeMillis() - (60 * 60 * 1000)
        cooldowns.entries.removeIf { entry -> entry.value < cooldownCutoff }

        // Clean up old player cooldowns (older than 1 hour)
        playerCooldowns.entries.removeIf { entry -> entry.value < cooldownCutoff }
    }
}
