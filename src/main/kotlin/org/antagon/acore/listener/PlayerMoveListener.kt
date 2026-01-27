package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.antagon.acore.util.MaterialValidator
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.*
import java.util.logging.Logger

class PlayerMoveListener : Listener {
    private val logger = Logger.getLogger(PlayerMoveListener::class.java.name)
    private val betterRunEnabled: Boolean
    private val smoothFactor: Double
    private val blockTypes: ConfigurationSection?
    private val checkFrequency: Int
    private val validBlocks: MutableMap<Material, Double> = HashMap()
    private val lastCheckTime: MutableMap<UUID, Long> = HashMap()
    private val lastBeehiveCheck: MutableMap<UUID, Long> = HashMap()

    init {
        val config = ConfigManager.getInstance()

        betterRunEnabled = config.getBoolean("betterRun.enabled", true)
        blockTypes = config.getSection("betterRun.block-types")
        smoothFactor = config.getDouble("betterRun.smooth-factor", 5.0)
        checkFrequency = config.getInt("betterRun.tick-frequency", 20)

        loadBlockTypes()
    }

    private fun loadBlockTypes() {
        if (blockTypes == null) {
            logger.warning("Warning: configuration section ‘betterRun.block-types’ not found!")
            return
        }
        for (key in blockTypes.getKeys(false)) {
            try {
                val blockType = MaterialValidator.validateMaterial(key)
                validBlocks[blockType] = blockTypes.getDouble(key)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid material in configuration: $key. ${e.message}")
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (!betterRunEnabled) return

        val player = event.player
        val playerId = player.uniqueId

        val currentTime = System.currentTimeMillis()
        val lastCheck = lastCheckTime.getOrDefault(playerId, 0L)
        if (currentTime - lastCheck < checkFrequency) return

        if (event.from.distanceSquared(event.to) < 0.01) return

        lastCheckTime[playerId] = currentTime

        val blockUnder = player.location.subtract(0.0, 0.1, 0.0).block
        val blockUnderType = blockUnder.type

        // Check for beehive achievement logic
        checkBeehiveAchievement(player, blockUnder)

        if (validBlocks.containsKey(blockUnderType)) {
            // Apply temporary speed effect
            if (!player.hasPotionEffect(PotionEffectType.SPEED)) {
                player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, 60, 0)) // 3 seconds, Speed I
            }
        } else {
            // Remove speed effect if player is not on valid block
            if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                player.removePotionEffect(PotionEffectType.SPEED)
            }
        }
    }

    // Check if player is standing on a beehive and award achievement if conditions are met
    private fun checkBeehiveAchievement(player: Player, blockUnder: Block) {
        // Check if the block under the player is a beehive
        if (blockUnder.type != Material.BEEHIVE && blockUnder.type != Material.BEE_NEST) {
            return
        }

        val playerId = player.uniqueId
        val currentTime = System.currentTimeMillis()
        val lastCheck = lastBeehiveCheck.getOrDefault(playerId, 0L)

        // Check cooldown (prevent spam)
        if (currentTime - lastCheck < 1000) { // 1 second cooldown
            return
        }

        lastBeehiveCheck[playerId] = currentTime

        // Check if player has the root achievement
        if (playerHasAchievement(player, "acore:schvapchichi/root")) {
            // Award the swarmer achievement
            awardAchievement(player, "acore:schvapchichi/swarmer")
        } else {
            logger.info("Player ${player.name} does not have root achievement")
        }
    }

    // Check if player has a specific achievement
    private fun playerHasAchievement(player: Player, advancementKey: String): Boolean {
        try {
            val key = NamespacedKey.fromString(advancementKey)
            if (key == null) {
                return false
            }

            val advancement = player.server.getAdvancement(key)
            if (advancement == null) {
                return false
            }

            return player.getAdvancementProgress(advancement).isDone
        } catch (e: Exception) {
            logger.warning("Error checking achievement $advancementKey: ${e.message}")
            return false
        }
    }

    // Award achievement to player
    private fun awardAchievement(player: Player, advancementKey: String) {
        try {
            val key = NamespacedKey.fromString(advancementKey)
            if (key == null) {
                return
            }

            val advancement = player.server.getAdvancement(key)
            if (advancement == null) {
                return
            }

            // Get available criteria for this advancement
            val criteria = advancement.criteria
            if (criteria.isEmpty()) {
                logger.warning("No criteria found for advancement: $advancementKey")
                return
            }

            // Award the first available criteria
            val firstCriteria = criteria.iterator().next()
            val progress = player.getAdvancementProgress(advancement)

            if (!progress.awardedCriteria.contains(firstCriteria)) {
                progress.awardCriteria(firstCriteria)
            } else {
                // Criteria already awarded
            }
        } catch (e: Exception) {
            logger.warning("Error awarding achievement $advancementKey: ${e.message}")
        }
    }
}