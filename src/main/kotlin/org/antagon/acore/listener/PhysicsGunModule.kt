package org.antagon.acore.listener

import com.github.retrooper.packetevents.PacketEvents
import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.util.DependencyHandler
import org.bukkit.FluidCollisionMode
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger

// movement is packet-only via a fake PacketEvents vehicle
class PhysicsGunModule(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    companion object {
        var current: PhysicsGunModule? = null
            private set
    }

    override val name: String = "Physics Gun"

    private val logger = Logger.getLogger(PhysicsGunModule::class.java.name)

    val gunItemService = GunItemService(plugin, configManager)
    val fakeVehicleService = FakeVehicleService(plugin)
    val grabManager = GrabManager(this)

    // built lazily in [enable]: instantiating this class eagerly in the
    // constructor would link PhysicsGunPacketListener (whose superclass
    // comes from PacketEvents) before shouldEnable() gets a chance to check
    // whether PacketEvents is even installed
    private var packetListener: PhysicsGunPacketListener? = null
    private var tickTask: BukkitTask? = null

    override fun shouldEnable(): Boolean {
        if (!configManager.getBoolean("physicsGun.enabled", true)) return false
        if (!DependencyHandler.isPluginEnabled("PacketEvents")) {
            logger.warning("PacketEvents is not installed or disabled - Physics Gun module skipped.")
            return false
        }
        return true
    }

    override fun enable() {
        current = this
        registerEvents(plugin)
        val listener = PhysicsGunPacketListener(this)
        packetListener = listener
        PacketEvents.getAPI().eventManager.registerListener(listener)
        tickTask = GrabTickTask(this).runTaskTimer(plugin, 1L, 1L)
    }

    override fun disable() {
        // order matters: stop packets/tasks first, then gracefully release everyone
        try {
            tickTask?.cancel()
            tickTask = null
            val listener = packetListener
            if (listener != null && DependencyHandler.isPluginEnabled("PacketEvents")) {
                PacketEvents.getAPI().eventManager.unregisterListener(listener)
            }
            packetListener = null
        } catch (e: Exception) {
            logger.fine("Packet listener unregister failed: ${e.message}")
        }
        grabManager.releaseAll()
        current = null
        super.disable()
    }

    // Direct right-click on a player with the gun. Debounced: the same click
    // can additionally produce a RIGHT_CLICK_AIR interact event
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!gunItemService.isGun(player.inventory.itemInMainHand)) return
        val target = event.rightClicked as? Player ?: return
        event.isCancelled = true
        if (!grabManager.markAction(player)) return
        grabManager.tryGrab(player, target)
    }

    // Right-click at air -> raytrace grab or gentle release; left-click -> throw
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!gunItemService.isGun(player.inventory.itemInMainHand)) return

        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> {
                if (grabManager.isHolder(player.uniqueId)) {
                    // LMB = throw the held player
                    event.isCancelled = true
                    grabManager.throwHeld(player.uniqueId)
                }
            }
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                // one physical click = one action, see markAction()
                if (!grabManager.markAction(player)) return
                val session = grabManager.getByHolder(player.uniqueId)
                if (session != null) {
                    // repeated RMB at air = gentle release
                    grabManager.release(player.uniqueId)
                } else {
                    // raytrace into the distance
                    val target = raytraceTarget(player) ?: return
                    grabManager.tryGrab(player, target)
                }
            }
            else -> Unit
        }
    }

    private fun raytraceTarget(holder: Player): Player? {
        val eye = holder.eyeLocation
        val result = holder.world.rayTrace(
            eye,
            eye.direction,
            grabManager.maxGrabDistance,
            FluidCollisionMode.NEVER,
            false,
            0.5
        ) { entity -> entity is Player && entity.uniqueId != holder.uniqueId }
        return result?.hitEntity as? Player
    }

    // direct punch of the held player also counts as throw, damage is cancelled
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return
        val session = grabManager.getByHolder(damager.uniqueId) ?: return
        if (session.heldId != victim.uniqueId) return
        event.isCancelled = true
        grabManager.throwHeld(damager.uniqueId)
    }

    // mouse wheel = hold distance adjustment; the slot switch itself is blocked
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val session = grabManager.getByHolder(event.player.uniqueId) ?: return
        event.isCancelled = true
        var delta = event.newSlot - event.previousSlot
        if (delta > 4) delta -= 9 else if (delta < -4) delta += 9
        if (delta != 0) {
            // scrolling up selects the previous slot and pulls the target closer
            grabManager.adjustDistance(session, delta)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        grabManager.handleQuit(event.player.uniqueId)
    }

    // relogging held players are re-captured (persistent-held). Packets are
    // sent with a small delay so the client has finished world init first
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val player = event.player
            if (player.isOnline) {
                grabManager.tryReattach(player)
            }
        }, 2L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        grabManager.releaseAllInvolving(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (configManager.getBoolean("physicsGun.grab.auto-release-on-world-change", true)) {
            grabManager.releaseAllInvolving(event.player.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val uuid = event.player.uniqueId
        if (grabManager.isInternalTeleport(uuid)) return
        // an external teleport of the HELD player always breaks the grab.
        // For the HOLDER, the tick task measures the resulting distance
        if (grabManager.isHeld(uuid)) {
            grabManager.releaseAllInvolving(uuid)
        }
    }

    // server-side dismounts of the held player are forbidden while grabbed
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val rider = event.entity as? Player ?: return
        if (grabManager.isHeld(rider.uniqueId)) {
            event.isCancelled = true
        }
    }

    // prevent the gun from leaving the main hand while a grab is active
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        if (grabManager.isHolder(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!grabManager.isHolder(event.player.uniqueId)) return
        if (gunItemService.isGun(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }
}
