package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.FallingBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector

class AnvilFallListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Anvil Fall Listener"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("anvilFall.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val startYKey = NamespacedKey(plugin, "anvil_start_y")
    private val anvils = setOf(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL)

    // Settings for the bounce feel
    companion object {
        private const val VELOCITY_PER_BLOCK = 0.09
        private const val MIN_BOUNCE = 0.05
        private const val MAX_BOUNCE = 2.5
        private const val STOP_THRESHOLD = 0.15
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onAnvilSpawn(event: EntitySpawnEvent) {
        val fallingBlock = event.entity as? FallingBlock ?: return
        if (!anvils.contains(fallingBlock.blockData.material)) return

        if (!fallingBlock.persistentDataContainer.has(startYKey, PersistentDataType.DOUBLE)) {
            fallingBlock.persistentDataContainer.set(startYKey, PersistentDataType.DOUBLE, fallingBlock.location.y)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onAnvilLand(event: EntityChangeBlockEvent) {
        val fallingBlock = event.entity as? FallingBlock ?: return
        if (!anvils.contains(fallingBlock.blockData.material)) return

        if (event.block.getRelative(BlockFace.DOWN).type != Material.SLIME_BLOCK) return

        val startY = fallingBlock.persistentDataContainer.get(startYKey, PersistentDataType.DOUBLE) ?: return

        val currentY = fallingBlock.location.y
        val blocksFallen = kotlin.math.max(0.0, startY - currentY)
        
        val bounceVel = kotlin.math.max(MIN_BOUNCE, kotlin.math.min(MAX_BOUNCE, VELOCITY_PER_BLOCK * blocksFallen))

        if (bounceVel < STOP_THRESHOLD) return

        event.isCancelled = true
        handleBounce(fallingBlock, bounceVel)
    }

    private fun handleBounce(original: FallingBlock, velocity: Double) {
        val loc = original.location
        
        val predictedPeakY = loc.y + (velocity * 8.0)

        original.remove()

        original.world.spawn(loc, FallingBlock::class.java) { spawned ->
            spawned.blockData = original.blockData
            spawned.setDropItem(original.dropItem)
            spawned.setHurtEntities(original.canHurtEntities())
            spawned.velocity = Vector(0.0, velocity, 0.0)
            
            spawned.persistentDataContainer.set(startYKey, PersistentDataType.DOUBLE, predictedPeakY)
        }

        original.world.playSound(loc, Sound.BLOCK_SLIME_BLOCK_FALL, 1.0f, 1.0f)
    }
}