package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.util.BlockInteractionTracker
import org.antagon.acore.util.EntityKillTracker
import org.bukkit.entity.Player
import org.bukkit.entity.ThrownPotion
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component

// Listens for indicator potion throws and displays players based on potion type
class IndicatorPotionListener(
    private val plugin: JavaPlugin,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Indicator Potions"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("indicatorPotions.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val blockTracker = BlockInteractionTracker.getInstance()
    private val entityTracker = EntityKillTracker.getInstance()

    // Enum for indicator potion types
    enum class PotionType(
        val configKey: String,
        val defaultCmd: Int,
        val messagePrefix: String,
        val colorCode: String
    ) {
        BLUE_INDICATOR("blue_indicator", 1185, "§9Игроки, ставившие блоки", "§9"),
        GREEN_INDICATOR("green_indicator", 1187, "§aИгроки, ломавшие блоки", "§a"),
        PINK_INDICATOR("pink_indicator", 1189, "§dИгроки, взаимодействовавшие с блоками", "§d"),
        RED_INDICATOR("red_indicator", 1191, "§cИгроки, убивавшие сущности", "§c")
    }

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

        // Check if this is our indicator potion and get its type
        val potionType = getPotionType(thrownPotion) ?: return
        
        // Check if this potion type is enabled in config
        val enabledPath = "indicatorPotions.potions.${potionType.configKey}.enabled"
        if (!configManager.getBoolean(enabledPath, true)) {
            return
        }

        // Check global enabled setting
        if (!configManager.getBoolean("indicatorPotions.enabled", true)) {
            return
        }

        // Check player-specific cooldown (global cooldown per player)
        val cooldown = configManager.getInt("indicatorPotions.cooldown", 30)

        if (blockTracker.isPlayerOnCooldown(player, cooldown)) {
            val remainingCooldown = blockTracker.getPlayerRemainingCooldown(player, cooldown)
            player.sendActionBar(Component.text("§cВы недавно использовали зелье! Подождите еще §e$remainingCooldown §cсекунд."))
            event.isCancelled = true // Cancel the potion throw
            return
        }

        // Set player cooldown immediately when potion is thrown
        blockTracker.setPlayerCooldown(player)

        val finalThrownPotion = thrownPotion

        // Schedule the effect after a short delay to let the potion land
        object : BukkitRunnable() {
            override fun run() {
                handleIndicatorPotionEffect(finalThrownPotion, player, potionType)
            }
        }.runTaskLater(plugin, 20L) // 1 second delay
    }

    private fun handleIndicatorPotionEffect(thrownPotion: ThrownPotion, player: Player, potionType: PotionType) {
        val effectLocation = thrownPotion.location

        // Get configuration values
        val radius = configManager.getInt("indicatorPotions.radius", 10)
        val displayDuration = configManager.getInt("indicatorPotions.display-duration", 10)
        val timeHours = configManager.getInt("indicatorPotions.time-lookup-hours", 12)

        // Get players based on potion type
        val players = when (potionType) {
            PotionType.BLUE_INDICATOR -> blockTracker.getPlayersWhoPlacedBlocks(effectLocation, radius, timeHours)
            PotionType.GREEN_INDICATOR -> blockTracker.getPlayersWhoBrokeBlocks(effectLocation, radius, timeHours)
            PotionType.PINK_INDICATOR -> blockTracker.getPlayersWhoInteracted(effectLocation, radius, timeHours)
            PotionType.RED_INDICATOR -> entityTracker.getPlayersWhoKilledEntities(effectLocation, radius, timeHours)
        }

        if (players.isEmpty()) {
            showActionBarForDuration(player, "§7В этом радиусе никого не обнаружено за последние §e$timeHours §7часов.", displayDuration)
            return
        }

        // Format message with players highlighted in the potion's color
        val playerList = players.joinToString("${potionType.colorCode}, ${potionType.colorCode}")
        val message = "${potionType.messagePrefix}: $potionType.colorCode$playerList"

        // Show in action bar for specified duration
        showActionBarForDuration(player, message, displayDuration)
    }

    // Gets the potion type based on Custom Model Data, or null if not an indicator potion
    private fun getPotionType(potion: ThrownPotion): PotionType? {
        // Get Custom Model Data from the potion item
        val customModelDataComponent = potion.item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)
        if (customModelDataComponent != null) {
            val cmd = customModelDataComponent.floats().firstOrNull()?.toInt() ?: -1
            
            // Check each potion type
            for (type in PotionType.entries) {
                val configCmd = configManager.getInt(
                    "indicatorPotions.potions.${type.configKey}.custom-model-data",
                    type.defaultCmd
                )
                if (cmd == configCmd) {
                    return type
                }
            }
        }

        return null
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
