package org.antagon.acore.feature.grab

import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.feature.physicsgun.OfflineTeleportRecord
import org.antagon.acore.feature.physicsgun.PhysicsGunModule
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

// sessions live in a single map keyed by holder UUID; "am I held?" checks are
// O(n) scans of the values. The number of simultaneous grabs is small (a few
// dozen at most), so a hand-maintained reverse index would only add an
// invariant to keep in sync without any measurable gain
class GrabManager(
    private val module: PhysicsGunModule
) {

    private val logger = Logger.getLogger(GrabManager::class.java.name)
    private val configManager: ConfigManager = ConfigManager.getInstance()

    // configuration (physicsGun.*)
    val maxGrabDistance = configManager.getDouble("physicsGun.grab.max-grab-distance", 30.0)
    private val defaultDistance = configManager.getDouble("physicsGun.grab.default-distance", 4.0)
    val minDistance = configManager.getDouble("physicsGun.grab.min-distance", 1.5)
    val maxDistance = configManager.getDouble("physicsGun.grab.max-distance", 15.0)
    private val distanceStep = configManager.getDouble("physicsGun.grab.distance-step", 0.5)
    val smoothingFactor = configManager.getDouble("physicsGun.grab.smoothing-factor", 0.35).coerceIn(0.01, 1.0)
    private val throwPower = configManager.getDouble("physicsGun.grab.throw-power", 2.5)
    private val allowCreativeTarget = configManager.getBoolean("physicsGun.grab.allow-creative-target", false)
    private val requireLineOfSight = configManager.getBoolean("physicsGun.grab.require-line-of-sight", true)
    val autoReleaseDistanceMultiplier = configManager.getDouble("physicsGun.grab.auto-release-distance-multiplier", 1.5)

    // keep the grab active when the HELD player relogs (re-captured on rejoin)
    private val persistentHeld = configManager.getBoolean("physicsGun.grab.persistent-held", true)

    // auto-release if a persisted held player stays offline longer than this (ms, 0 = unlimited)
    val persistentMaxMillis = configManager.getInt("physicsGun.grab.persistent-max-seconds", 300) * 1000L

    private val soundGrab = configManager.getString("physicsGun.effects.sound-grab", "")
    private val soundRelease = configManager.getString("physicsGun.effects.sound-release", "")

    private val sessions = ConcurrentHashMap<UUID, GrabSession>()

    // debounce guard for right-clicks. CraftBukkit can deliver one physical
    // click as both org.bukkit.event.player.PlayerInteractEntityEvent and
    // org.bukkit.event.player.PlayerInteractEvent RIGHT_CLICK_AIR - without
    // this guard a grab would be released again by the second event
    private val lastActionTick = HashMap<UUID, Int>()

    // guard set marking teleports initiated by this module itself, so that
    // org.bukkit.event.player.PlayerTeleportEvent caused by our own
    // position sync is not treated as an external one (main thread only)
    private val internalTeleports = HashSet<UUID>()

    // active grab sessions by holder UUID (read by the tick task / command)
    val activeSessions: Collection<GrabSession> get() = sessions.values

    // cheap emptiness probe - lets the packet listener bail out before any work
    val hasActiveSessions: Boolean get() = sessions.isNotEmpty()

    fun getByHolder(holderId: UUID): GrabSession? = sessions[holderId]
    fun getByHeld(heldId: UUID): GrabSession? = sessions.values.firstOrNull { it.heldId == heldId }
    fun isHolder(uuid: UUID): Boolean = sessions.containsKey(uuid)
    fun isHeld(uuid: UUID): Boolean = sessions.values.any { it.heldId == uuid }
    fun isInternalTeleport(uuid: UUID): Boolean = internalTeleports.contains(uuid)

    fun markInternalTeleport(uuid: UUID) { internalTeleports.add(uuid) }
    fun unmarkInternalTeleport(uuid: UUID) { internalTeleports.remove(uuid) }

    // marks a grab-related interaction for this tick
    fun markAction(player: Player): Boolean {
        val tick = player.server.currentTick
        if (lastActionTick[player.uniqueId] == tick) return false
        lastActionTick[player.uniqueId] = tick
        if (lastActionTick.size > 64) {
            lastActionTick.entries.removeIf { it.value < tick - 20 }
        }
        return true
    }

    // attempts to start a grab of target by holder
    fun tryGrab(holder: Player, target: Player): Boolean {
        if (!holder.hasPermission("acore.physicsgun.use")) {
            sendMessage(holder, "no-permission")
            return false
        }
        if (holder.uniqueId == target.uniqueId) return false
        if (isHolder(holder.uniqueId)) return false       // already holding someone
        if (isHeld(target.uniqueId)) return false         // already held by someone
        if (isHolder(target.uniqueId)) return false       // mutual grab protection
        if (target.world != holder.world) return false
        if (target.hasPermission("acore.physicsgun.immune")) {
            sendMessage(holder, "physicsgun-target-immune")
            return false
        }
        if (!allowCreativeTarget && (target.gameMode == GameMode.CREATIVE || target.gameMode == GameMode.SPECTATOR)) {
            return false
        }
        if (holder.location.distanceSquared(target.location) > maxGrabDistance * maxGrabDistance) {
            return false
        }
        if (requireLineOfSight && !hasLineOfSight(holder, target)) {
            return false
        }

        // Dismount target from vehicle if mounted
        if (target.isInsideVehicle) {
            target.leaveVehicle()
        }

        val fakeEntityId = module.fakeVehicleService.generateEntityId()
        val session = GrabSession(
            holderId = holder.uniqueId,
            heldId = target.uniqueId,
            fakeEntityId = fakeEntityId,
            // start at the target's current position: the smoothing in the tick
            // task then smoothly pulls it to the hold point
            currentPos = target.location.toVector(),
            distance = defaultDistance.coerceIn(minDistance, maxDistance),
            world = holder.world,
            lastTickTime = System.currentTimeMillis()
        )
        sessions[holder.uniqueId] = session

        // initial spawn for players currently in the visibility radius
        module.fakeVehicleService.syncViewers(session, target.entityId)

        // server-side safety measures on the REAL entity
        target.setGravity(false)
        target.fallDistance = 0f

        playConfiguredSound(soundGrab, holder.world, holder.eyeLocation.toVector())
        return true
    }

    // no non-transparent blocks between the holder and the target (block raytrace, fluids ignored)
    fun hasLineOfSight(holder: Player, target: Player): Boolean {
        val eye = holder.eyeLocation
        val toTarget = target.eyeLocation.toVector().subtract(eye.toVector())
        val distance = toTarget.length()
        if (distance < 1e-6) return true
        val hit = holder.world.rayTraceBlocks(eye, toTarget.clone().normalize(), distance, FluidCollisionMode.NEVER, true)
        return hit == null
    }

    fun adjustDistance(session: GrabSession, wheelDelta: Int) {
        session.distance = (session.distance + wheelDelta * distanceStep).coerceIn(minDistance, maxDistance)
    }

    // release by holder UUID, optionally applying the throw impulse
    fun release(holderId: UUID, applyThrow: Boolean = false) {
        val session = sessions.remove(holderId) ?: return

        module.fakeVehicleService.destroyForAll(session)

        val held = Bukkit.getPlayer(session.heldId)
        if (held != null && held.isOnline) {
            // the real entity was frozen server-side during the grab, so move it to where the player visibly was
            markInternalTeleport(session.heldId)
            try {
                held.teleport(session.currentPos.toLocation(session.world, held.location.yaw, held.location.pitch))
            } finally {
                unmarkInternalTeleport(session.heldId)
            }
            held.setGravity(true)
            held.fallDistance = 0f
            if (applyThrow) {
                val direction = Bukkit.getPlayer(session.holderId)?.eyeLocation?.direction ?: Vector(0, 0, 0)
                held.velocity = direction.clone().multiply(throwPower)
            }
        } else {
            // Target is offline: persist their latest position to SQLite so they spawn here upon reconnect
            module.offlineTeleportDao.save(
                OfflineTeleportRecord(
                    uuid = session.heldId,
                    world = session.world.name,
                    x = session.currentPos.x,
                    y = session.currentPos.y,
                    z = session.currentPos.z,
                    yaw = 0f,
                    pitch = 0f
                )
            )
        }
        playConfiguredSound(soundRelease, session.world, session.currentPos)
    }

    // throw = release with an impulse along the holder's view direction
    fun throwHeld(holderId: UUID) = release(holderId, applyThrow = true)

    // force-release whatever grab involves this player
    fun releaseAllInvolving(uuid: UUID) {
        if (isHolder(uuid)) release(uuid)
        getByHeld(uuid)?.let { release(it.holderId) }
    }

    // the drop of a HOLDER always ends the grab; a quitting HELD player spawns a ghost
    fun handleQuit(uuid: UUID) {
        if (isHolder(uuid)) {
            release(uuid)
            return
        }
        val session = getByHeld(uuid) ?: return
        if (persistentHeld) {
            val player = Bukkit.getPlayer(uuid)
            session.heldOfflineSince = System.currentTimeMillis()

            // Snapshot skin and profile
            val profile = player?.playerProfile
            val textureProp = profile?.properties?.firstOrNull { it.name == "textures" }
            session.cachedTextures = if (textureProp != null) Pair(textureProp.value, textureProp.signature ?: "") else null
            session.cachedName = player?.name ?: ""
            session.ghostEntityId = module.fakeVehicleService.generateEntityId()
            session.ghostUuid = UUID.randomUUID()
            session.textDisplayEntityId = module.fakeVehicleService.generateEntityId()

            // Destroy old vehicle for viewers and spawn the ghost vehicle
            module.fakeVehicleService.destroyForAll(session)
            module.fakeVehicleService.syncViewers(session, session.ghostEntityId!!)
        } else {
            release(session.holderId)
        }
    }

    // re-captures a relogging held player
    fun tryReattach(player: Player): Boolean {
        val session = getByHeld(player.uniqueId) ?: return false
        if (session.heldOfflineSince == null) return false
        val holder = Bukkit.getPlayer(session.holderId)
        if (holder == null || !holder.isOnline || player.world != session.world) {
            release(session.holderId)
            return false
        }

        // Clean up offline ghost packets
        module.fakeVehicleService.destroyForAll(session)
        session.ghostEntityId = null
        session.ghostUuid = null
        session.textDisplayEntityId = null
        session.heldOfflineSince = null

        // Teleport returning player to current ghost position
        markInternalTeleport(player.uniqueId)
        try {
            player.teleport(session.currentPos.toLocation(session.world, player.location.yaw, player.location.pitch))
        } finally {
            unmarkInternalTeleport(player.uniqueId)
        }

        player.setGravity(false)
        player.fallDistance = 0f
        module.fakeVehicleService.syncViewers(session, player.entityId)
        playConfiguredSound(soundGrab, session.world, session.currentPos)
        return true
    }

    // full cleanup on module disable / plugin shutdown
    fun releaseAll() {
        for (holderId in sessions.keys.toList()) {
            release(holderId)
        }
        sessions.clear()
        internalTeleports.clear()
        lastActionTick.clear()
    }

    fun sendMessage(player: Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        Acore.instance.localizationManager.send(player, key, placeholders)
    }

    fun sendActionBar(player: Player, key: String, placeholders: Map<String, String> = emptyMap()) {
        val component = Acore.instance.localizationManager.getComponent(key, player, placeholders)
        player.sendActionBar(component)
    }

    private fun playConfiguredSound(soundId: String, world: World, at: Vector) {
        if (soundId.isBlank()) return
        if (NamespacedKey.fromString(soundId) == null) {
            logger.fine("Invalid sound id '$soundId' in physicsGun.effects config section")
            return
        }
        world.playSound(at.toLocation(world), soundId, 1.0f, 1.0f)
    }
}
