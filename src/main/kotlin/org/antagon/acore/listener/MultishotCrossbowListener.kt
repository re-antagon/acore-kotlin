package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class MultishotCrossbowListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val config: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Multishot Crossbow Improvement"

    override fun shouldEnable(): Boolean {
        return config.getBoolean("multishotImprovement.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val shotKey = NamespacedKey(plugin, "multishot_shot_id")
    private val burstHits: MutableMap<String, Int> = ConcurrentHashMap()

    @EventHandler(priority = EventPriority.NORMAL)
    fun onEntityShootBow(event: EntityShootBowEvent) {
        val bow = event.bow
        val shooter = event.entity

        val item = if (bow != null && bow.type == Material.CROSSBOW) {
            bow
        } else {
            val equipment = shooter.equipment
            when {
                equipment?.itemInMainHand?.type == Material.CROSSBOW -> equipment.itemInMainHand
                equipment?.itemInOffHand?.type == Material.CROSSBOW -> equipment.itemInOffHand
                else -> null
            }
        } ?: return

        // Check if item has MULTISHOT enchantment
        val hasMultishot = item.getEnchantmentLevel(Enchantment.MULTISHOT) > 0 ||
                (item.hasItemMeta() && item.itemMeta.hasEnchant(Enchantment.MULTISHOT))

        if (!hasMultishot) return

        val projectile = event.projectile as? AbstractArrow ?: return

        // Unique burst identifier for this shooter + tick combination
        val shotId = "${shooter.uniqueId}:${Bukkit.getCurrentTick()}"

        projectile.persistentDataContainer.set(shotKey, PersistentDataType.STRING, shotId)

        // Schedule cleanup after expirationTicks (default 40 ticks = 2s) to prevent memory leaks under high load
        val expirationTicks = config.getInt("multishotImprovement.expirationTicks", 40).toLong()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            burstHits.keys.removeIf { key -> key.startsWith("$shotId:") }
        }, expirationTicks)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        val shotId = arrow.persistentDataContainer.get(shotKey, PersistentDataType.STRING) ?: return
        val target = event.hitEntity as? LivingEntity ?: return

        val key = "$shotId:${target.uniqueId}"
        val hitCount = burstHits.getOrDefault(key, 0)

        // If target was already hit by an arrow from this burst, reset invulnerability frames
        if (hitCount >= 1) {
            target.noDamageTicks = 0
            target.lastDamage = 0.0
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        val shotId = arrow.persistentDataContainer.get(shotKey, PersistentDataType.STRING) ?: return
        val target = event.entity as? LivingEntity ?: return

        val key = "$shotId:${target.uniqueId}"

        // If vanilla cancelled damage due to invulnerability, un-cancel it for our multishot arrows
        if (event.isCancelled) {
            event.isCancelled = false
        }

        // Reset target i-frames again to guarantee subsequent arrows hit
        target.noDamageTicks = 0
        target.lastDamage = 0.0

        val currentHit = burstHits.compute(key) { _, count -> (count ?: 0) + 1 } ?: 1

        val firstMultiplier = config.getDouble("multishotImprovement.firstArrowMultiplier", 1.0)
        val secondMultiplier = config.getDouble("multishotImprovement.secondArrowMultiplier", 0.8)
        val thirdMultiplier = config.getDouble("multishotImprovement.thirdArrowMultiplier", 0.9)

        val multiplier = when (currentHit) {
            1 -> firstMultiplier
            2 -> secondMultiplier
            3 -> thirdMultiplier
            else -> 0.0
        }

        event.damage = event.damage * multiplier
    }
}
