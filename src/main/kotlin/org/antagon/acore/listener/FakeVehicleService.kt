package org.antagon.acore.listener

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Optional
import java.util.UUID

// packet-only fake vehicle management via PacketEvents.
// For every grab session an invisible, no-gravity, marker ArmorStand is
// spawned *only on the clients* (it never exists in the Bukkit world, never
// fires CreatureSpawnEvent and is invisible to other plugins). The held
// player is attached to it as a passenger via WrapperPlayServerSetPassengers,
// so the vanilla client interpolates the movement itself - the visuals are
// smooth without any client-side teleports of the real player entity.
// Entity metadata indices are the 1.21 protocol values:
//  - index 0  (BYTE)    shared entity flags, bit 0x20 = Invisible
//  - index 5  (BOOLEAN) No gravity
//  - index 15 (BYTE)    ArmorStand flags: 0x10 Marker | 0x08 No base plate | 0x01 Small
class FakeVehicleService(
    private val plugin: Plugin
) {

    companion object {
        private const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        private const val ARMOR_STAND_FLAGS: Byte = (0x10 or 0x08 or 0x01).toByte()
    }

    // radius (blocks) in which clients get to see the fake vehicle physicsGun.visibility.radius
    private val visibilityRadius = ConfigManager.getInstance().getDouble("physicsGun.visibility.radius", 64.0)

    fun generateEntityId(): Int = SpigotReflectionUtil.generateEntityId()

    private fun buildMetadata(): List<EntityData<*>> {
        @Suppress("UNCHECKED_CAST")
        return listOf(
            EntityData(0, EntityDataTypes.BYTE, ENTITY_FLAG_INVISIBLE) as EntityData<*>,
            EntityData(5, EntityDataTypes.BOOLEAN, true) as EntityData<*>,
            EntityData(15, EntityDataTypes.BYTE, ARMOR_STAND_FLAGS) as EntityData<*>
        )
    }

    private fun send(player: Player, wrapper: PacketWrapper<*>) {
        try {
            PacketEvents.getAPI().playerManager.sendPacket(player, wrapper)
        } catch (t: Throwable) {
            plugin.logger.fine("[PhysicsGun] Failed to send packet to ${player.name}: ${t.message}")
        }
    }

    // spawns the fake vehicle + attaches heldPlayer for a single viewer
    fun spawnFor(viewer: Player, session: GrabSession, heldEntityId: Int) {
        val pos = session.currentPos
        val spawn = WrapperPlayServerSpawnEntity(
            session.fakeEntityId,
            Optional.of(UUID.randomUUID()),
            EntityTypes.ARMOR_STAND,
            Vector3d(pos.x, pos.y, pos.z),
            0f, 0f, 0f,
            0,
            Optional.empty()
        )
        send(viewer, spawn)
        send(viewer, WrapperPlayServerEntityMetadata(session.fakeEntityId, buildMetadata()))
        send(viewer, WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(heldEntityId)))
    }

    // sends DestroyEntities for the fake vehicle to a single viewer
    fun destroyFor(viewer: Player, session: GrabSession) {
        send(viewer, WrapperPlayServerDestroyEntities(session.fakeEntityId))
    }

    // teleports the fake vehicle to GrabSession.currentPos for all current viewers
    fun broadcastTeleport(session: GrabSession) {
        if (session.viewers.isEmpty()) return
        val pos = session.currentPos
        val teleport = WrapperPlayServerEntityTeleport(
            session.fakeEntityId,
            Vector3d(pos.x, pos.y, pos.z),
            0f, 0f,
            false
        )
        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            send(viewer, teleport)
        }
    }

    // re-sends the passengers packet to all current viewers.
    // Cheap insurance against client-side predicted dismounts and any
    // missed state
    fun rebroadcastPassengers(session: GrabSession, heldEntityId: Int) {
        if (session.viewers.isEmpty()) return
        val passengers = WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(heldEntityId))
        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            send(viewer, passengers)
        }
    }

    // recomputes the viewer set from the fake vehicle position:
    // same-world players within visibilityRadius; the held player is
    // always included (his own client must believe he is riding).
    // New viewers get spawn packets, gone viewers get destroy packets
    fun syncViewers(session: GrabSession, heldEntityId: Int) {
        val world = session.world
        val pos = session.currentPos
        val radiusSquared = visibilityRadius * visibilityRadius

        val wanted = HashSet<UUID>()
        for (p in world.players) {
            if (p.location.distanceSquared(pos.toLocation(world)) <= radiusSquared) {
                wanted.add(p.uniqueId)
            }
        }
        wanted.add(session.heldId)

        // spawn for newcomers
        for (uuid in wanted) {
            if (uuid in session.viewers) continue
            val viewer = Bukkit.getPlayer(uuid) ?: continue
            spawnFor(viewer, session, heldEntityId)
        }
        // destroy for those who left the radius
        for (uuid in session.viewers) {
            if (uuid in wanted) continue
            val viewer = Bukkit.getPlayer(uuid) ?: continue
            destroyFor(viewer, session)
        }

        session.viewers.clear()
        session.viewers.addAll(wanted)
    }

    // per-tick update: position sync, viewer diff
    fun tick(session: GrabSession, heldEntityId: Int) {
        syncViewers(session, heldEntityId)
        broadcastTeleport(session)
    }

    // destroys the fake vehicle for all known viewers
    fun destroyForAll(session: GrabSession) {
        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            destroyFor(viewer, session)
        }
        session.viewers.clear()
    }
}
