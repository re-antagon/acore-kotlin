package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import kotlin.random.Random

class BlockBurnListener(
    private val plugin: Plugin = Acore.instance,
    private val config: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Fire Adjustment"

    override fun shouldEnable(): Boolean {
        return config.getBoolean("fireAdjustment.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }
    private data class Drop(val item: Material, val min: Int, val max: Int, val chance: Double)
    private val drops = mutableMapOf<Material, Drop>()

    init {
        config.getSection("fireAdjustment.block-drops")?.getKeys(false)?.forEach { category ->
            val path = "fireAdjustment.block-drops.$category"
            val item = Material.matchMaterial(config.getString("$path.item", "")) ?: return@forEach
            val drop = Drop(item, config.getInt("$path.min-amount", 1), config.getInt("$path.max-amount", 1),
                config.getDouble("$path.chance", config.getDouble("$path.drop-chance", 0.0)))
            config.getStringList("$path.blocks").forEach { name -> Material.matchMaterial(name)?.let { drops[it] = drop } }
        }
    }

    @EventHandler
    fun onBurn(event: BlockBurnEvent) {
        if (!config.getBoolean("fireAdjustment.enabled", true)) return
        val drop = drops[event.block.type] ?: return
        if (Random.nextDouble() < drop.chance) {
            val amount = Random.nextInt(drop.min, (drop.max + 1).coerceAtLeast(drop.min + 1))
            event.block.world.dropItemNaturally(event.block.location, ItemStack(drop.item, amount))
        }
    }
}
