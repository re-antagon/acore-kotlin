package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.event.Listener
import java.util.logging.Logger

class CopperOxidationListener(
    private val plugin: JavaPlugin,
    private val configManager: ConfigManager
) : Listener {

    private val logger = Logger.getLogger(CopperOxidationListener::class.java.name)

    private val enabled: Boolean
    private val waterMultiplier: Double
    private val rainMultiplier: Double
    private val checkInterval: Long
    private val scanRadius: Int

    // Map of copper material -> next oxidation stage
    private val oxidationStages: Map<Material, Material> = mutableMapOf<Material, Material>().apply {
        // Regular copper blocks
        put(Material.COPPER_BLOCK, Material.EXPOSED_COPPER)
        put(Material.EXPOSED_COPPER, Material.WEATHERED_COPPER)
        put(Material.WEATHERED_COPPER, Material.OXIDIZED_COPPER)

        // Cut copper
        put(Material.CUT_COPPER, Material.EXPOSED_CUT_COPPER)
        put(Material.EXPOSED_CUT_COPPER, Material.WEATHERED_CUT_COPPER)
        put(Material.WEATHERED_CUT_COPPER, Material.OXIDIZED_CUT_COPPER)

        // Copper bulbs
        put(Material.COPPER_BULB, Material.EXPOSED_COPPER_BULB)
        put(Material.EXPOSED_COPPER_BULB, Material.WEATHERED_COPPER_BULB)
        put(Material.WEATHERED_COPPER_BULB, Material.OXIDIZED_COPPER_BULB)

        // Doors
        put(Material.COPPER_DOOR, Material.EXPOSED_COPPER_DOOR)
        put(Material.EXPOSED_COPPER_DOOR, Material.WEATHERED_COPPER_DOOR)
        put(Material.WEATHERED_COPPER_DOOR, Material.OXIDIZED_COPPER_DOOR)

        // Trapdoors
        put(Material.COPPER_TRAPDOOR, Material.EXPOSED_COPPER_TRAPDOOR)
        put(Material.EXPOSED_COPPER_TRAPDOOR, Material.WEATHERED_COPPER_TRAPDOOR)
        put(Material.WEATHERED_COPPER_TRAPDOOR, Material.OXIDIZED_COPPER_TRAPDOOR)

        // Stairs (added in 1.21, may not exist on older servers)
        try {
            put(Material.valueOf("COPPER_STAIRS"), Material.valueOf("EXPOSED_COPPER_STAIRS"))
            put(Material.valueOf("EXPOSED_COPPER_STAIRS"), Material.valueOf("WEATHERED_COPPER_STAIRS"))
            put(Material.valueOf("WEATHERED_COPPER_STAIRS"), Material.valueOf("OXIDIZED_COPPER_STAIRS"))
        } catch (_: IllegalArgumentException) {}

        // Slabs (added in 1.21, may not exist on older servers)
        try {
            put(Material.valueOf("COPPER_SLAB"), Material.valueOf("EXPOSED_COPPER_SLAB"))
            put(Material.valueOf("EXPOSED_COPPER_SLAB"), Material.valueOf("WEATHERED_COPPER_SLAB"))
            put(Material.valueOf("WEATHERED_COPPER_SLAB"), Material.valueOf("OXIDIZED_COPPER_SLAB"))
        } catch (_: IllegalArgumentException) {}
    }.toMap()

    init {
        enabled = configManager.getBoolean("copperOxidation.enabled", true)
        waterMultiplier = configManager.getDouble("copperOxidation.water-speed-multiplier", 4.0)
        rainMultiplier = configManager.getDouble("copperOxidation.rain-speed-multiplier", 2.0)
        checkInterval = configManager.getInt("copperOxidation.check-interval", 40).toLong()
        scanRadius = configManager.getInt("copperOxidation.scan-radius", 48)

        if (enabled) {
            startOxidationTask()
            logger.info("Copper Oxidation acceleration task started (interval: ${checkInterval} ticks)")
        }
    }

    private fun startOxidationTask() {
        object : BukkitRunnable() {
            override fun run() {
                accelerateCopperOxidation()
            }
        }.runTaskTimer(plugin, 20L, checkInterval)
    }

    private fun accelerateCopperOxidation() {
        if (!enabled) return

        val onlinePlayers = Bukkit.getOnlinePlayers()
        if (onlinePlayers.isEmpty()) return

        for (player in onlinePlayers) {
            if (!player.isOnline) continue

            val world = player.world
            val centerX = player.location.blockX
            val centerY = player.location.blockY
            val centerZ = player.location.blockZ

            // Scan in a box around the player
            val minX = centerX - scanRadius
            val maxX = centerX + scanRadius
            val minZ = centerZ - scanRadius
            val maxZ = centerZ + scanRadius
            val minY = (centerY - scanRadius).coerceAtLeast(world.minHeight)
            val maxY = (centerY + scanRadius).coerceAtMost(world.maxHeight - 1)

            // Step by 2 blocks to reduce CPU load
            for (x in minX..maxX step 3) {
                for (z in minZ..maxZ step 3) {
                    for (y in minY..maxY step 2) {
                        try {
                            val block = world.getBlockAt(x, y, z)
                            if (oxidationStages.containsKey(block.type)) {
                                checkAndOxidize(block)
                            }
                        } catch (e: Exception) {
                            // Skip invalid blocks
                        }
                    }
                }
            }
        }
    }

    private fun checkAndOxidize(block: Block) {
        val currentType = block.type
        val nextType = oxidationStages[currentType] ?: return

        val inWater = isSubmergedInWater(block)
        val underRain = isUnderRain(block)

        if (!inWater && !underRain) return

        // Base chance per check (about 2%)
        var chance = 0.02

        if (inWater) {
            chance *= waterMultiplier
        }
        if (underRain) {
            chance *= rainMultiplier
        }

        // Cap the chance at reasonable value
        chance = chance.coerceAtMost(0.85)

        if (Math.random() < chance) {
            // Oxidize the block
            block.type = nextType

            // Optional: play sound effect
            try {
                block.world.playSound(
                    block.location,
                    Sound.BLOCK_COPPER_PLACE,
                    0.6f,
                    0.8f + (Math.random() * 0.4f).toFloat()
                )
            } catch (_: Exception) {
                // Sound not available, skip
            }
        }
    }

    private fun isSubmergedInWater(block: Block): Boolean {
        // Check if waterlogged
        val blockData = block.blockData
        if (blockData is Waterlogged && blockData.isWaterlogged) {
            return true
        }

        // Check adjacent blocks for water
        val faces = listOf(
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.EAST,
            BlockFace.SOUTH, BlockFace.WEST
        )

        for (face in faces) {
            val relative = block.getRelative(face)
            if (relative.type == Material.WATER ||
                relative.type == Material.WATER_CAULDRON ||
                relative.type == Material.KELP ||
                relative.type == Material.SEAGRASS) {
                return true
            }
        }

        // Check if the block itself is in water (for non-waterlogged cases)
        val above = block.getRelative(BlockFace.UP)
        if (above.type == Material.WATER) {
            return true
        }

        return false
    }

    private fun isUnderRain(block: Block): Boolean {
        val world = block.world

        // Check if it's raining/storming
        if (!world.hasStorm()) {
            return false
        }

        // Check if the block has sky access (no solid blocks directly above)
        var y = block.y + 1
        val maxY = world.maxHeight

        while (y < maxY) {
            val aboveBlock = world.getBlockAt(block.x, y, block.z)
            val type = aboveBlock.type

            // Consider solid blocks as blocking rain (except transparent ones)
            val typeName = type.name
            if (type.isSolid &&
                !typeName.contains("LEAVES")) {
                return false
            }
            y++
        }

        return true
    }

    private fun isCopper(material: Material): Boolean {
        return oxidationStages.containsKey(material)
    }
}