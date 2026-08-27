package org.antagon.acore.listener

import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

// per-tick update of every active grab session
class GrabTickTask(
    private val module: PhysicsGunModule
) : BukkitRunnable() {

    companion object {
        private const val PASSENGERS_RESYNC_INTERVAL = 20L
        private const val ACTIONBAR_INTERVAL = 5L
    }

    private val grabManager = module.grabManager

    private val particleEnabled = ConfigManager.getInstance().getBoolean("physicsGun.effects.particle-enabled", true)
    private val particleInterval = ConfigManager.getInstance().getInt("physicsGun.effects.particle-interval-ticks", 4).coerceAtLeast(1)
    private val particleType = try {
        Particle.valueOf(ConfigManager.getInstance().getString("physicsGun.effects.particle-type", "END_ROD").uppercase())
    } catch (e: IllegalArgumentException) {
        Particle.END_ROD
    }

    private var tickCounter = 0L

    override fun run() {
        tickCounter++
        for (session in grabManager.activeSessions.toList()) {
            updateSession(session)
        }
    }

    private fun updateSession(session: GrabSession) {
        val holder = Bukkit.getPlayer(session.holderId)
        if (holder == null || !holder.isOnline) {
            grabManager.release(session.holderId)
            return
        }

        if (holder.world != session.world) {
            grabManager.release(session.holderId)
            return
        }

        // permission revoked mid-hold
        if (!holder.hasPermission("acore.physicsgun.use")) {
            grabManager.release(session.holderId)
            return
        }

        val offlineSince = session.heldOfflineSince
        if (offlineSince != null) {
            if (grabManager.persistentMaxMillis > 0 &&
                System.currentTimeMillis() - offlineSince > grabManager.persistentMaxMillis
            ) {
                grabManager.release(session.holderId)
                return
            }

            // check break distance for offline ghost
            val breakDistance = grabManager.maxGrabDistance * grabManager.autoReleaseDistanceMultiplier
            if (holder.location.distanceSquared(session.currentPos.toLocation(holder.world)) > breakDistance * breakDistance) {
                grabManager.release(session.holderId)
                return
            }

            // move hold point
            val eye = holder.eyeLocation
            val desired = eye.toVector().add(eye.direction.clone().multiply(session.distance))
            session.currentPos.add(desired.subtract(session.currentPos).multiply(grabManager.smoothingFactor))
            session.lastTickTime = System.currentTimeMillis()

            val ghostId = session.ghostEntityId ?: 0
            module.fakeVehicleService.tick(session, ghostId)

            if (tickCounter % PASSENGERS_RESYNC_INTERVAL == 0L) {
                module.fakeVehicleService.rebroadcastPassengers(session, ghostId)
            }

            // Pulse ghost outline every 10 ticks (0.5s)
            if (tickCounter % 10L == 0L) {
                session.glowingPulseWhite = !session.glowingPulseWhite
                module.fakeVehicleService.pulseGhostOutline(session)
            }

            // action bar feedback for holder
            if (tickCounter % ACTIONBAR_INTERVAL == 0L) {
                grabManager.sendActionBar(holder, "physicsgun-holder-distance-notice", mapOf("distance" to String.format("%.1f", session.distance)))
            }

            // beam particles
            if (particleEnabled && tickCounter % particleInterval == 0L) {
                spawnBeam(eye.toVector(), session.currentPos, session)
            }
            return
        }

        val held = Bukkit.getPlayer(session.heldId)
        if (held == null || !held.isOnline) {
            grabManager.release(session.holderId)
            return
        }
        if (holder.world != held.world) {
            grabManager.release(session.holderId)
            return
        }

        // holder teleported away
        val breakDistance = grabManager.maxGrabDistance * grabManager.autoReleaseDistanceMultiplier
        if (holder.location.distanceSquared(session.currentPos.toLocation(holder.world)) > breakDistance * breakDistance) {
            grabManager.release(session.holderId)
            return
        }

        // desired hold point: holder eye position + view direction * distance
        val eye = holder.eyeLocation
        val desired = eye.toVector().add(eye.direction.clone().multiply(session.distance))

        // exponential approach smoothing
        session.currentPos.add(desired.subtract(session.currentPos).multiply(grabManager.smoothingFactor))
        session.lastTickTime = System.currentTimeMillis()

        // the real entity is NOT touched during the grab - incoming
        // position packets of the held player are cancelled by the
        // packet listener, so its server-side position simply stays frozen
        // where the grab started. All motion is visual-only via the fake
        // vehicle; the real entity is synced once on release GrabManager#release
        held.fallDistance = 0f // fall damage protection while held

        // fake vehicle packets
        module.fakeVehicleService.tick(session, held.entityId)
        if (tickCounter % PASSENGERS_RESYNC_INTERVAL == 0L) {
            module.fakeVehicleService.rebroadcastPassengers(session, held.entityId)
        }

        // action bar feedback
        if (tickCounter % ACTIONBAR_INTERVAL == 0L) {
            grabManager.sendActionBar(holder, "physicsgun-holder-distance-notice", mapOf("distance" to String.format("%.1f", session.distance)))
            grabManager.sendActionBar(held, "physicsgun-grabbed-self-notice")
        }

        // beam particles
        if (particleEnabled && tickCounter % particleInterval == 0L) {
            spawnBeam(eye.toVector(), session.currentPos, session)
        }
    }

    private fun spawnBeam(from: Vector, to: Vector, session: GrabSession) {
        val direction = to.clone().subtract(from)
        val length = direction.length()
        if (length < 0.5) return
        val steps = (length / 0.7).toInt().coerceAtMost(48)
        val step = direction.multiply(1.0 / steps)
        val point = from.clone()
        for (i in 0..steps) {
            session.world.spawnParticle(particleType, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
            point.add(step)
        }
    }
}
