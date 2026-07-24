package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.util.BlockInteractionTracker
import org.bukkit.entity.Player
import org.bukkit.entity.ThrownPotion
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component

/**
 * Listens for fog potion throws and displays last players who interacted with blocks in radius
 */
class FogPotionListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Fog Potion"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("fogPotion.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val tracker = BlockInteractionTracker.getInstance()

    @EventHandler
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        if (event.entity !is ThrownPotion) {
            return
        }

        val thrownPotion = event.entity as ThrownPotion

        // Try to get the player who threw the potion
        if (thrownPotion.shooter !is Player) {
            return
        }

        val player = thrownPotion.shooter as Player

        // Check if this is our custom fog potion by Custom Model Data
        val customModelData = getCustomModelData(thrownPotion)
        val configuredCustomModelData = configManager.getInt("fogPotion.custom-model-data", 1001)

        if (customModelData != configuredCustomModelData) {
            return
        }

        // Check if potion is enabled in config
        if (!configManager.getBoolean("fogPotion.enabled", true)) {
            return
        }

        // Check player-specific cooldown (global cooldown per player)
        val cooldown = configManager.getInt("fogPotion.cooldown", 30)

        if (tracker.isPlayerOnCooldown(player, cooldown)) {
            val remainingCooldown = tracker.getPlayerRemainingCooldown(player, cooldown)
            player.sendMessage("§cВы недавно использовали зелье! Подождите еще §e$remainingCooldown §cсекунд.")
            event.isCancelled = true // Cancel the potion throw
            return
        }

        // Set player cooldown immediately when potion is thrown
        tracker.setPlayerCooldown(player)

        val finalThrownPotion = thrownPotion

        // Schedule the effect after a short delay to let the potion land
        object : BukkitRunnable() {
            override fun run() {
                handleFogPotionEffect(finalThrownPotion, player)
            }
        }.runTaskLater(plugin, 20L) // 1 second delay
    }

    private fun handleFogPotionEffect(thrownPotion: ThrownPotion, player: Player) {
        val effectLocation = thrownPotion.location

        // Get configuration values
        val radius = configManager.getInt("fogPotion.radius", 10)
        val displayDuration = configManager.getInt("fogPotion.display-duration", 10)

        // Get last players in radius
        val lastPlayers = tracker.getLastPlayersInRadius(effectLocation, radius)

        if (lastPlayers.isEmpty()) {
            player.sendMessage("§7В этом радиусе никто не взаимодействовал с блоками недавно.")
            return
        }

        // Format message - all player names in white color
        val playerList = lastPlayers.joinToString("§f, §f")
        val message = "§aПоследние взаимодействия в радиусе: §f$playerList"

        // Show in action bar for specified duration
        showActionBarForDuration(player, message, displayDuration)
    }

    private fun getCustomModelData(potion: ThrownPotion): Int {
        // Get Custom Model Data from the potion item to identify it as our fog potion
        val customModelDataComponent = potion.item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)
        if (customModelDataComponent != null) {
            val firstFloat = customModelDataComponent.floats().firstOrNull()
            if (firstFloat != null) {
                return firstFloat.toInt()
            }
        }

        return -1 // Return -1 if no Custom Model Data is found
    }

    private fun showActionBarForDuration(player: Player, message: String, seconds: Int) {
        val component = Component.text(message)
        // Show initial message
        player.sendActionBar(component)

        if (seconds > 0) {
            // Schedule repeated messages
            object : BukkitRunnable() {
                private var remaining = seconds

                override fun run() {
                    if (remaining <= 0 || !player.isOnline) {
                        cancel()
                        return
                    }

                    player.sendActionBar(component)
                    remaining--
                }
            }.runTaskTimer(plugin, 20L, 20L) // Every second
        }
    }
}