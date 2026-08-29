package org.antagon.acore.feature.blocks

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.event.Listener
import java.util.logging.Logger

class CopperOxidationListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Copper Oxidation Acceleration"

    private val logger = Logger.getLogger(CopperOxidationListener::class.java.name)

    private val waterMultiplier: Double
    private val rainMultiplier: Double
    private val checkInterval: Long
    private val scanRadius: Int
    private var task: BukkitTask? = null

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

        // Cut copper stairs
        put(Material.CUT_COPPER_STAIRS, Material.EXPOSED_CUT_COPPER_STAIRS)
        put(Material.EXPOSED_CUT_COPPER_STAIRS, Material.WEATHERED_CUT_COPPER_STAIRS)
        put(Material.WEATHERED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER_STAIRS)

        // Cut copper slabs
        put(Material.CUT_COPPER_SLAB, Material.EXPOSED_CUT_COPPER_SLAB)
        put(Material.EXPOSED_CUT_COPPER_SLAB, Material.WEATHERED_CUT_COPPER_SLAB)
        put(Material.WEATHERED_CUT_COPPER_SLAB, Material.OXIDIZED_CUT_COPPER_SLAB)

        // Copper grates
        put(Material.COPPER_GRATE, Material.EXPOSED_COPPER_GRATE)
        put(Material.EXPOSED_COPPER_GRATE, Material.WEATHERED_COPPER_GRATE)
        put(Material.WEATHERED_COPPER_GRATE, Material.OXIDIZED_COPPER_GRATE)

        // Chiseled copper
        put(Material.CHISELED_COPPER, Material.EXPOSED_CHISELED_COPPER)
        put(Material.EXPOSED_CHISELED_COPPER, Material.WEATHERED_CHISELED_COPPER)
        put(Material.WEATHERED_CHISELED_COPPER, Material.OXIDIZED_CHISELED_COPPER)
    }.toMap()

    init {
        waterMultiplier = configManager.getDouble("copperOxidation.water-speed-multiplier", 4.0)
        rainMultiplier = configManager.getDouble("copperOxidation.rain-speed-multiplier", 2.0)
        checkInterval = configManager.getInt("copperOxidation.check-interval", 40).toLong()
        scanRadius = configManager.getInt("copperOxidation.scan-radius", 48)
    }

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("copperOxidation.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
        startOxidationTask()
    }

    override fun disable() {
        super.disable()
        task?.cancel()
        task = null
    }

    private fun startOxidationTask() {
        task = object : BukkitRunnable() {
            override fun run() {
                accelerateCopperOxidation()
            }
        }.runTaskTimer(plugin, 20L, checkInterval)
    }

    private fun accelerateCopperOxidation() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        if (onlinePlayers.isEmpty()) return

        val random = java.util.concurrent.ThreadLocalRandom.current()

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

            // Perform 300 random samples in the scan volume instead of checking every block in grid.
            // This is O(1) CPU load per player and fixes the grid-alignment bug where certain blocks were never checked.
            repeat(300) {
                try {
                    val rx = random.nextInt(minX, maxX + 1)
                    val rz = random.nextInt(minZ, maxZ + 1)
                    val ry = random.nextInt(minY, maxY + 1)

                    val block = world.getBlockAt(rx, ry, rz)
                    if (oxidationStages.containsKey(block.type)) {
                        checkAndOxidize(block)
                    }
                } catch (_: Exception) {
                    // Skip invalid coordinates/blocks
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

        // Query chunk heightmap in O(1) time
        val highestY = world.getHighestBlockYAt(block.x, block.z)
        if (highestY <= block.y) {
            return true
        }

        // Check blocks only between block.y + 1 and highestY (usually 0 to a few blocks)
        var y = block.y + 1
        while (y <= highestY) {
            val aboveBlock = world.getBlockAt(block.x, y, block.z)
            val type = aboveBlock.type

            // Consider solid blocks as blocking rain (except transparent ones)
            val typeName = type.name
            if (type.isSolid && !typeName.contains("LEAVES")) {
                return false
            }
            y++
        }

        return true
    }
}