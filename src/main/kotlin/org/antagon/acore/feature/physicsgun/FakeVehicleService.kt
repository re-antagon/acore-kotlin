package org.antagon.acore.feature.physicsgun

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.antagon.acore.feature.grab.GrabSession
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.Optional
import java.util.UUID

// packet-only fake vehicle management via PacketEvents.
// For every grab session an invisible, no-gravity, marker ArmorStand is
// spawned *only on the clients* (it never exists in the Bukkit world, never
// fires CreatureSpawnEvent and is invisible to other plugins). The held
// player is attached to it as a passenger via WrapperPlayServerSetPassengers,
// so the vanilla client interpolates the movement itself - the visuals are
// smooth without any client-side teleports of the real player entity.
// For offline held players, a fake Player ghost with their skin and a TextDisplay
// tag is spawned and mounted to the fake vehicle instead.
// Entity metadata indices are the 1.21 protocol values:
//  - index 0  (BYTE)    shared entity flags, bit 0x20 = Invisible, bit 0x40 = Glowing
//  - index 5  (BOOLEAN) No gravity
//  - index 15 (BYTE)    ArmorStand flags: 0x10 Marker | 0x08 No base plate | 0x01 Small
class FakeVehicleService(
    private val plugin: Plugin
) {

    companion object {
        private const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        private const val ENTITY_FLAG_GLOWING: Byte = 0x40
        private const val ARMOR_STAND_FLAGS: Byte = (0x10 or 0x08 or 0x01).toByte()
    }

    // radius (blocks) in which clients get to see the fake vehicle physicsGun.visibility.radius
    private val visibilityRadius = ConfigManager.getInstance().getDouble("physicsGun.visibility.radius", 64.0)

    fun generateEntityId(): Int = SpigotReflectionUtil.generateEntityId()

    private fun buildVehicleMetadata(): List<EntityData<*>> {
        @Suppress("UNCHECKED_CAST")
        return listOf(
            EntityData(0, EntityDataTypes.BYTE, ENTITY_FLAG_INVISIBLE) as EntityData<*>,
            EntityData(5, EntityDataTypes.BOOLEAN, true) as EntityData<*>,
            EntityData(15, EntityDataTypes.BYTE, ARMOR_STAND_FLAGS) as EntityData<*>
        )
    }

    private fun buildGhostMetadata(): List<EntityData<*>> {
        @Suppress("UNCHECKED_CAST")
        return listOf(
            EntityData(0, EntityDataTypes.BYTE, ENTITY_FLAG_GLOWING) as EntityData<*>
        )
    }

    private fun buildTextDisplayMetadata(): List<EntityData<*>> {
        val tagComponent = Acore.instance.localizationManager.getComponent("physicsgun-offline-ghost-tag")
        @Suppress("UNCHECKED_CAST")
        return listOf(
            EntityData(15, EntityDataTypes.BYTE, 3.toByte()) as EntityData<*>, // Billboard: CENTER
            EntityData(23, EntityDataTypes.ADV_COMPONENT, tagComponent) as EntityData<*>
        )
    }

    private fun send(player: Player, wrapper: PacketWrapper<*>) {
        try {
            PacketEvents.getAPI().playerManager.sendPacket(player, wrapper)
        } catch (t: Throwable) {
            plugin.logger.warning("[PhysicsGun] Failed to send packet ${wrapper.javaClass.simpleName} to ${player.name}: ${t.message}")
        }
    }

    // spawns the fake vehicle (+ attached real player or offline ghost) for a single viewer
    fun spawnFor(viewer: Player, session: GrabSession, heldEntityId: Int) {
        val pos = session.currentPos
        val spawnVehicle = WrapperPlayServerSpawnEntity(
            session.fakeEntityId,
            Optional.of(UUID.randomUUID()),
            EntityTypes.ARMOR_STAND,
            Vector3d(pos.x, pos.y, pos.z),
            0f, 0f, 0f,
            0,
            Optional.of(Vector3d.zero())
        )
        send(viewer, spawnVehicle)
        send(viewer, WrapperPlayServerEntityMetadata(session.fakeEntityId, buildVehicleMetadata()))

        if (session.heldOfflineSince != null) {
            // Spawn offline ghost player + text display
            val ghostId = session.ghostEntityId ?: return
            val ghostUuid = session.ghostUuid ?: return
            val textDisplayId = session.textDisplayEntityId ?: return

            val textures = session.cachedTextures
            val textureProps = if (textures != null) {
                listOf(TextureProperty("textures", textures.first, textures.second))
            } else {
                emptyList()
            }

            val userProfile = UserProfile(ghostUuid, session.cachedName.ifEmpty { "Ghost" }, textureProps)
            val playerInfo = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                userProfile,
                false, // not listed in tab
                0,
                GameMode.SURVIVAL,
                null,
                null
            )
            val infoPacket = WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER),
                listOf(playerInfo)
            )
            send(viewer, infoPacket)

            val spawnGhost = WrapperPlayServerSpawnEntity(
                ghostId,
                Optional.of(ghostUuid),
                EntityTypes.PLAYER,
                Vector3d(pos.x, pos.y, pos.z),
                0f, 0f, 0f,
                0,
                Optional.of(Vector3d.zero())
            )
            send(viewer, spawnGhost)
            send(viewer, WrapperPlayServerEntityMetadata(ghostId, buildGhostMetadata()))

            val spawnText = WrapperPlayServerSpawnEntity(
                textDisplayId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY,
                Vector3d(pos.x, pos.y + 2.15, pos.z),
                0f, 0f, 0f,
                0,
                Optional.of(Vector3d.zero())
            )
            send(viewer, spawnText)
            send(viewer, WrapperPlayServerEntityMetadata(textDisplayId, buildTextDisplayMetadata()))

            // Setup glowing outline team for the ghost
            sendTeamPacket(viewer, session, isCreate = true)

            // Mount ghost and text display on the vehicle
            send(viewer, WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(ghostId, textDisplayId)))
        } else {
            send(viewer, WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(heldEntityId)))
        }
    }

    private fun getTeamName(session: GrabSession): String {
        return "acore_pg_${session.fakeEntityId % 100000}"
    }

    private fun sendTeamPacket(viewer: Player, session: GrabSession, isCreate: Boolean) {
        val teamName = getTeamName(session)
        val color = if (session.glowingPulseWhite) NamedTextColor.WHITE else NamedTextColor.GRAY
        val teamInfo = WrapperPlayServerTeams.ScoreBoardTeamInfo(
            Component.text(teamName),
            Component.empty(),
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            color,
            WrapperPlayServerTeams.OptionData.NONE
        )

        val teamPacket = if (isCreate) {
            val memberName = session.cachedName.ifEmpty { session.ghostUuid?.toString() ?: "" }
            WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.CREATE,
                teamInfo,
                if (memberName.isNotEmpty()) listOf(memberName) else emptyList()
            )
        } else {
            WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.UPDATE,
                teamInfo,
                emptyList()
            )
        }
        send(viewer, teamPacket)
    }

    // pulses outline color between WHITE and GRAY
    fun pulseGhostOutline(session: GrabSession) {
        if (session.viewers.isEmpty()) return
        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            sendTeamPacket(viewer, session, isCreate = false)
        }
    }

    // sends DestroyEntities for the fake vehicle and any ghost entities to a single viewer
    fun destroyFor(viewer: Player, session: GrabSession) {
        val destroyIds = mutableListOf(session.fakeEntityId)
        session.ghostEntityId?.let { destroyIds.add(it) }
        session.textDisplayEntityId?.let { destroyIds.add(it) }

        send(viewer, WrapperPlayServerDestroyEntities(*destroyIds.toIntArray()))

        session.ghostUuid?.let { ghostUuid ->
            send(viewer, WrapperPlayServerPlayerInfoRemove(listOf(ghostUuid)))
            val removeTeam = WrapperPlayServerTeams(
                getTeamName(session),
                WrapperPlayServerTeams.TeamMode.REMOVE,
                null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
                emptyList()
            )
            send(viewer, removeTeam)
        }
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

    // re-sends the passengers packet to all current viewers
    fun rebroadcastPassengers(session: GrabSession, heldEntityId: Int) {
        if (session.viewers.isEmpty()) return
        val passengers = if (session.heldOfflineSince != null) {
            val ghostId = session.ghostEntityId
            val textId = session.textDisplayEntityId
            if (ghostId != null && textId != null) {
                WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(ghostId, textId))
            } else {
                WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(heldEntityId))
            }
        } else {
            WrapperPlayServerSetPassengers(session.fakeEntityId, intArrayOf(heldEntityId))
        }

        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            send(viewer, passengers)
        }
    }

    // recomputes viewer set and sends diff packets
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
        if (session.heldOfflineSince == null) {
            wanted.add(session.heldId)
        }

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

    fun tick(session: GrabSession, heldEntityId: Int) {
        syncViewers(session, heldEntityId)
        broadcastTeleport(session)
    }

    fun destroyForAll(session: GrabSession) {
        for (viewerId in session.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            destroyFor(viewer, session)
        }
        session.viewers.clear()
    }
}

