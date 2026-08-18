package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.module.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Piston
import org.bukkit.entity.FallingBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector

class PistonLaunchAnvilListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Piston Launch Anvil"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("pistonLaunchAnvil.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val startYKey = NamespacedKey(plugin, "anvil_start_y")
    private val activeVoids = mutableMapOf<Location, BlockData>()
    private val anvils = setOf(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL)
    private val pistons = setOf(Material.PISTON, Material.STICKY_PISTON)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        val piston = event.block
        if (!pistons.contains(piston.type)) return
        if (!piston.isBlockPowered && !piston.isBlockIndirectlyPowered) return
        
        val pistonData = piston.blockData as? Piston ?: return
        if (pistonData.isExtended) return
        if (pistonData.facing != BlockFace.UP) return

        val anvilBlock = piston.getRelative(BlockFace.UP, 2)
        if (!anvils.contains(anvilBlock.type)) return

        val anvilLoc = anvilBlock.location
        if (activeVoids.containsKey(anvilLoc)) return

        val data = anvilBlock.blockData
        activeVoids[anvilLoc] = data
        anvilBlock.type = Material.AIR

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val dataToRestore = activeVoids.remove(anvilLoc)
            if (dataToRestore != null) {
                if (anvilLoc.block.type == Material.AIR || anvils.contains(anvilLoc.block.type)) {
                    anvilLoc.block.blockData = dataToRestore
                } else {
                    anvilLoc.world.dropItemNaturally(anvilLoc, ItemStack(dataToRestore.material))
                }
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val expectedAnvilLoc = event.block.getRelative(BlockFace.UP, 2).location
        val data = activeVoids.remove(expectedAnvilLoc) ?: return
        launchAnvilEntity(expectedAnvilLoc.block, data)
    }

    private fun launchAnvilEntity(targetBlock: Block, data: BlockData) {
        val spawnLoc = targetBlock.location.add(0.5, 0.2, 0.5)
        
        targetBlock.world.spawn(spawnLoc, FallingBlock::class.java) { spawned ->
            spawned.blockData = data
            spawned.setDropItem(true)
            spawned.setHurtEntities(true)
            spawned.velocity = Vector(0.0, 1.2, 0.0)

            val predictedPeak = spawnLoc.y + (1.2 * 8.0)
            spawned.persistentDataContainer.set(startYKey, PersistentDataType.DOUBLE, predictedPeak)
        }
    }
}