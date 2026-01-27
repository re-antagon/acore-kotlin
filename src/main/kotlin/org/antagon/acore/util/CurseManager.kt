package org.antagon.acore.util

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.collections.HashSet

class CurseManager(private val plugin: JavaPlugin) {
    private val curseFile: File
    val cursedPlayers: MutableSet<UUID> = HashSet()
    private lateinit var curseConfig: YamlConfiguration

    init {
        curseFile = File(plugin.dataFolder, "cursed_players.yml")
        loadCursedPlayers()
    }

    // Load cursed players from file
    private fun loadCursedPlayers() {
        if (!curseFile.exists()) {
            try {
                curseFile.createNewFile()
                curseConfig = YamlConfiguration.loadConfiguration(curseFile)
                curseConfig.set("cursed-players", ArrayList<String>())
                curseConfig.save(curseFile)
            } catch (e: IOException) {
                plugin.logger.severe("Failed to create cursed players file: " + e.message)
                return
            }
        }

        curseConfig = YamlConfiguration.loadConfiguration(curseFile)
        val cursedUUIDs = curseConfig.getStringList("cursed-players")

        for (uuidStr in cursedUUIDs) {
            try {
                cursedPlayers.add(UUID.fromString(uuidStr))
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Invalid UUID in cursed players file: $uuidStr")
            }
        }

        plugin.logger.info("Loaded " + cursedPlayers.size + " cursed players")
    }

    // Save cursed players to file
    private fun saveCursedPlayers() {
        val cursedUUIDs = ArrayList<String>()
        for (uuid in cursedPlayers) {
            cursedUUIDs.add(uuid.toString())
        }

        curseConfig.set("cursed-players", cursedUUIDs)

        try {
            curseConfig.save(curseFile)
        } catch (e: IOException) {
            plugin.logger.severe("Failed to save cursed players file: " + e.message)
        }
    }

    // Add player to cursed list
    fun addCursedPlayer(playerId: UUID) {
        cursedPlayers.add(playerId)
        saveCursedPlayers()
    }

    // Remove player from cursed list
    fun removeCursedPlayer(playerId: UUID) {
        cursedPlayers.remove(playerId)
        saveCursedPlayers()
    }

    // Check if player is cursed
    fun isPlayerCursed(playerId: UUID): Boolean {
        return cursedPlayers.contains(playerId)
    }

    // Get all cursed players
    fun getCursedPlayers(): Set<UUID> {
        return HashSet(cursedPlayers)
    }
}
