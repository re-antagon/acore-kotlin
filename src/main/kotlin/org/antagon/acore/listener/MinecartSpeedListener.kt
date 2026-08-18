package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.util.MaterialValidator
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.EntityType
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.plugin.Plugin
import java.util.*
import java.util.logging.Logger

class MinecartSpeedListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Minecart Speed"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("minecartSpeed.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val logger = Logger.getLogger(MinecartSpeedListener::class.java.name)
    private val smoothFactor: Double
    private val minecartTypes: List<String>
    private val blockTypes: ConfigurationSection?
    private val railTypes: ConfigurationSection?
    private val validBlocks: MutableMap<Material, Double> = HashMap()
    private val validRails: MutableMap<Material, Double> = HashMap()
    private val validMinecarts: EnumSet<EntityType>
    private val railTypesSet = EnumSet.of(
        Material.RAIL, Material.POWERED_RAIL,
        Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL
    )

    init {
        blockTypes = configManager.getSection("minecartSpeed.block-types")
        railTypes = configManager.getSection("minecartSpeed.rail-types")
        smoothFactor = configManager.getDouble("minecartSpeed.smooth-factor", 5.0)
        minecartTypes = configManager.getStringList("minecartSpeed.minecart-types")

        loadBlockTypes()
        loadRailTypes()
        validMinecarts = loadMinecartTypes()
    }

    private fun loadBlockTypes() {
        if (blockTypes == null) {
            logger.warning("Warning: configuration section 'minecartSpeed.block-types' not found!")
            return
        }
        for (key in blockTypes.getKeys(false)) {
            try {
                val blockType = MaterialValidator.validateMaterial(key)
                validBlocks[blockType] = blockTypes.getDouble(key)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid material in block-types: $key. ${e.message}")
            }
        }
    }

    private fun loadRailTypes() {
        if (railTypes == null) {
            logger.warning("Warning: configuration section 'minecartSpeed.rail-types' not found!")
            return
        }
        for (key in railTypes.getKeys(false)) {
            try {
                val railType = MaterialValidator.validateMaterial(key)
                validRails[railType] = railTypes.getDouble(key)
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid material in rail-types: $key. ${e.message}")
            }
        }
    }

    private fun loadMinecartTypes(): EnumSet<EntityType> {
        val set = EnumSet.noneOf(EntityType::class.java)
        for (name in minecartTypes) {
            try {
                set.add(EntityType.valueOf(name))
            } catch (e: IllegalArgumentException) {
                logger.warning("Invalid entity type in minecart-types: $name. ${e.message}")
            }
        }
        if (set.isEmpty()) {
            logger.warning("Warning: configuration list 'minecartSpeed.minecart-types' is empty or not found!")
        }
        return set
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onVehicleMove(event: VehicleMoveEvent) {
        if (event.vehicle !is Minecart) return
        val minecart = event.vehicle as Minecart
        if (!validMinecarts.contains(minecart.type)) return

        // Check if minecart is empty or passenger is not a player
        if (minecart.isEmpty || minecart.passengers[0] !is Player) return

        val railBlock = event.vehicle.location.block
        if (!railTypesSet.contains(railBlock.type)) return

        val blockBelow = railBlock.getRelative(0, -1, 0)
        val blockMultiplier = validBlocks.getOrDefault(blockBelow.type, 1.0)
        val railMultiplier = validRails.getOrDefault(railBlock.type, 1.0)
        val totalMultiplier = blockMultiplier * railMultiplier

        if (Math.abs(totalMultiplier - 1.0) < 0.001) return

        // Use maxSpeed instead of velocity manipulation for proper minecart speed control
        val newMaxSpeed = VANILLA_MAX_SPEED * totalMultiplier
        minecart.maxSpeed = newMaxSpeed
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) {
        if (event.vehicle !is Minecart) return
        if (event.exited !is Player) return
        val minecart = event.vehicle as Minecart

        if (minecart.maxSpeed > VANILLA_MAX_SPEED) {
            minecart.maxSpeed = VANILLA_MAX_SPEED
        }
    }

    companion object {
        private const val VANILLA_MAX_SPEED = 0.4
    }
}