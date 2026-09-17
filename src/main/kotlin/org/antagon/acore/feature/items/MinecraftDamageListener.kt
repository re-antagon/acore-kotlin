package org.antagon.acore.feature.items

import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.core.ConfigManager
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Vehicle
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.logging.Logger

class MinecartDamageListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Minecart Damage"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("minecartDamage.enabled", true)
    }

    private val logger = Logger.getLogger(MinecartDamageListener::class.java.name)

    private val damageAmount: Double
    private val speedThreshold: Double
    private val hitCooldownTicks: Long
    private val knockbackStrength: Double
    private val minecartTypes: List<String>
    private val validMinecarts: EnumSet<EntityType>

    private val lastHitTick = HashMap<String, Long>()

    init {
        damageAmount = configManager.getDouble("minecartDamage.damage-amount", 1.0)
        speedThreshold = configManager.getDouble("minecartDamage.speed-threshold", 0.4)
        hitCooldownTicks = configManager.getInt("minecartDamage.hit-cooldown-ticks", 10).coerceAtLeast(1).toLong()
        knockbackStrength = configManager.getDouble("minecartDamage.knockback", 0.5)
        minecartTypes = configManager.getStringList("minecartDamage.minecart-types")
        validMinecarts = loadMinecartTypes()
    }

    private fun loadMinecartTypes(): EnumSet<EntityType> {
        val set = EnumSet.noneOf(EntityType::class.java)
        for (name in minecartTypes) {
            try {
                set.add(EntityType.valueOf(name))
            } catch (_: IllegalArgumentException) {
                logger.warning("Invalid entity type in minecartDamage.minecart-types: $name")
            }
        }
        if (set.isEmpty()) {
            logger.warning(
                "Warning: configuration list 'minecartDamage.minecart-types' is empty or not found, " +
                    "the feature will do nothing."
            )
        }
        return set
    }

    override fun enable() {
        registerEvents(plugin)
    }

    override fun disable() {
        super.disable()
        lastHitTick.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val vehicle = event.vehicle
        if (vehicle !is Minecart) return
        if (!validMinecarts.contains(vehicle.type)) return

        val speed = vehicle.velocity.length()
        if (!isFastEnough(speed, speedThreshold)) return

        val damage = computeDamage(damageAmount, speed)
        if (damage <= 0.0) return

        val target = findCollisionTarget(vehicle) ?: return
        if (isInvulnerable(target)) return

        val currentTick = plugin.server.currentTick.toLong()
        val pairKey = vehicle.uniqueId.toString() + ":" + target.entityId
        val lastHit = lastHitTick[pairKey]
        if (lastHit != null && currentTick - lastHit < hitCooldownTicks) return
        lastHitTick[pairKey] = currentTick

        target.damage(damage, vehicle)

        if (knockbackStrength > 0.0) {
            val knock = vehicle.velocity.clone().normalize().multiply(knockbackStrength)
            target.velocity = target.velocity.add(knock)
        }

        if (lastHitTick.size > MAX_TRACKED_PAIRS) {
            purgeStaleEntries(currentTick)
        }
    }

    private fun findCollisionTarget(cart: Minecart): LivingEntity? {
        val cartLocation = cart.location
        val velocity = cart.velocity
        val probe = cartLocation.clone().add(velocity.clone().multiply(PROBE_TICKS_AHEAD))
        val nearby = cart.getNearbyEntities(COLLISION_BOX_EXPANSION, COLLISION_BOX_EXPANSION, COLLISION_BOX_EXPANSION)
        val cartPos = cartLocation.toVector()
        val travel = velocity.clone()

        var best: LivingEntity? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in nearby) {
            val living = entity as? LivingEntity ?: continue
            if (!isDamageable(living, cart)) continue
            if (living.location.toVector().subtract(cartPos).dot(travel) <= 0.0) continue
            val distance = distanceFromProbe(living.location, probe)
            if (distance < bestDistance) {
                bestDistance = distance
                best = living
            }
        }
        return best
    }

    private fun isDamageable(entity: LivingEntity, cart: Minecart): Boolean {
        if (entity === cart) return false
        if (entity is Vehicle) return false
        if (cart.passengers.contains(entity)) return false
        return true
    }

    private fun isInvulnerable(entity: Entity): Boolean {
        if (entity.isInvulnerable()) return true
        if (entity is Player) {
            return entity.gameMode == org.bukkit.GameMode.CREATIVE || entity.gameMode == org.bukkit.GameMode.SPECTATOR
        }
        return false
    }

    private fun distanceFromProbe(from: Location, probe: Location): Double {
        if (from.world != probe.world) return Double.MAX_VALUE
        return from.distanceSquared(probe)
    }

    private fun purgeStaleEntries(currentTick: Long) {
        lastHitTick.entries.retainAll { entry -> currentTick - entry.value < hitCooldownTicks }
    }

    companion object {
        private const val SPEED_REFERENCE = 0.2
        private const val COLLISION_BOX_EXPANSION = 0.75
        private const val PROBE_TICKS_AHEAD = 1.0
        private const val MAX_TRACKED_PAIRS = 4096

        fun computeDamage(damageAmount: Double, speed: Double): Double {
            if (damageAmount <= 0.0 || speed <= 0.0) return 0.0
            return damageAmount * (speed / SPEED_REFERENCE)
        }

        fun isFastEnough(speed: Double, threshold: Double): Boolean = speed >= threshold
    }
}
