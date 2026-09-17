package org.antagon.acore.feature.player

import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Logger

class BetterRunListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Better Run"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("betterRun.enabled", true)
    }

    private val logger = Logger.getLogger(BetterRunListener::class.java.name)

    private val smoothFactor: Double
    private val tickFrequency: Long
    private val sprintOnly: Boolean
    private val blockTypes: ConfigurationSection?
    private val validBlocks: MutableMap<Material, Double> = HashMap()

    private val lastCheckTick = HashMap<UUID, Long>()
    private val lastBlockUnder = HashMap<UUID, Material>()
    private val activeMultiplier = HashMap<UUID, Double>()

    init {
        blockTypes = configManager.getSection("betterRun.block-types")
        smoothFactor = configManager.getDouble("betterRun.smooth-factor", 5.0)
        tickFrequency = configManager.getInt("betterRun.tick-frequency", 20).coerceAtLeast(1).toLong()
        sprintOnly = configManager.getBoolean("betterRun.sprint-only", true)

        loadBlockTypes()
    }

    private fun loadBlockTypes() {
        val section = blockTypes
        if (section == null) {
            logger.warning("Warning: configuration section 'betterRun.block-types' not found!")
            return
        }
        for (key in section.getKeys(false)) {
            val material = Material.matchMaterial(key)
            if (material == null) {
                logger.warning("Invalid material in betterRun.block-types: $key")
                continue
            }
            val bonus = section.getDouble(key)
            if (bonus <= 0.0) {
                logger.warning("Non-positive speed bonus in betterRun.block-types: $key = $bonus")
                continue
            }
            validBlocks[material] = bonus
        }
        if (validBlocks.isEmpty()) {
            logger.warning("Warning: 'betterRun.block-types' has no usable entries, the feature will do nothing.")
        }
    }

    override fun enable() {
        registerEvents(plugin)
    }

    override fun disable() {
        super.disable()
        for (player in plugin.server.onlinePlayers) {
            if (activeMultiplier.containsKey(player.uniqueId)) {
                player.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(MODIFIER_KEY)
            }
        }
        lastCheckTick.clear()
        lastBlockUnder.clear()
        activeMultiplier.clear()
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.from.distanceSquared(event.to) < MIN_MOVE_DISTANCE_SQ) return

        val player = event.player
        val playerId = player.uniqueId

        if (sprintOnly && !player.isSprinting && !activeMultiplier.containsKey(playerId)) return

        val currentTick = player.ticksLived.toLong()
        val blockUnder = player.location.subtract(0.0, MOVE_PROBE_OFFSET, 0.0).block.type
        val blockChanged = lastBlockUnder[playerId] != blockUnder
        val lastCheck = lastCheckTick[playerId]
        if (!blockChanged && lastCheck != null && currentTick - lastCheck < tickFrequency) return
        lastCheckTick[playerId] = currentTick
        lastBlockUnder[playerId] = blockUnder

        updateSpeed(player, blockUnder, player.isSprinting)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onToggleSprint(event: PlayerToggleSprintEvent) {
        val player = event.player
        val blockUnder = player.location.subtract(0.0, MOVE_PROBE_OFFSET, 0.0).block.type
        lastBlockUnder[player.uniqueId] = blockUnder
        updateSpeed(player, blockUnder, event.isSprinting)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        lastCheckTick.remove(event.player.uniqueId)
        lastBlockUnder.remove(event.player.uniqueId)
        activeMultiplier.remove(event.player.uniqueId)
    }

    private fun updateSpeed(player: Player, blockUnder: Material, sprinting: Boolean) {
        val eligible = !sprintOnly || sprinting
        val bonus = if (eligible) validBlocks[blockUnder] else null

        val target = bonus?.let { BASE_MULTIPLIER + it } ?: BASE_MULTIPLIER
        val current = activeMultiplier[player.uniqueId] ?: BASE_MULTIPLIER
        val smoothed = nextMultiplier(current, target, smoothFactor)

        val settled = isAtTarget(smoothed, target)
        val changed = if (settled) current != target else smoothed != current

        if (changed) {
            if (settled) {
                activeMultiplier.remove(player.uniqueId)
            } else {
                activeMultiplier[player.uniqueId] = smoothed
            }
            applyMultiplier(player, smoothed)
        }
    }

    private fun applyMultiplier(player: Player, multiplier: Double) {
        val attribute: AttributeInstance = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.removeModifier(MODIFIER_KEY)
        if (isAtTarget(multiplier, BASE_MULTIPLIER)) return

        attribute.addModifier(
            AttributeModifier(
                MODIFIER_KEY,
                multiplier - BASE_MULTIPLIER,
                AttributeModifier.Operation.ADD_NUMBER
            )
        )
    }

    companion object {
        private const val BASE_MULTIPLIER = 1.0
        private const val MIN_MOVE_DISTANCE_SQ = 0.01
        private const val MOVE_PROBE_OFFSET = 0.1

        private const val EPSILON = 1.0E-5

        private val MODIFIER_KEY = NamespacedKey("acore", "better_run_speed")

        fun smoothingAlpha(smoothFactor: Double): Double = 1.0 / (1.0 + smoothFactor.coerceAtLeast(0.0))

        fun nextMultiplier(current: Double, target: Double, smoothFactor: Double): Double {
            if (isAtTarget(current, target)) return target
            val alpha = smoothingAlpha(smoothFactor)
            return current + (target - current) * alpha
        }

        fun isAtTarget(value: Double, target: Double): Boolean = Math.abs(value - target) < EPSILON
    }
}
