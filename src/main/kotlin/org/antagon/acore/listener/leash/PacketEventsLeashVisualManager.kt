package org.antagon.acore.listener.leash

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.antagon.acore.core.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Optional
import java.util.UUID

// packetEvents-backed client-side leash rope renderer
class PacketEventsLeashVisualManager(
    private val plugin: Plugin,
    private val configManager: ConfigManager
) : LeashVisualManager {

    companion object {
        private const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
    }

    override val visualEnabled: Boolean = true

    private val visualRadius: Double
        get() = configManager.getDouble("leashPlayers.leash.visual-broadcast-radius", 48.0)

    private val fakeEntityType: EntityType by lazy {
        val configured = configManager.getString("leashPlayers.leash.fake-entity-type", "BAT").uppercase()
        try {
            EntityTypes::class.java.getField(configured).get(null) as EntityType
        } catch (_: Throwable) {
            plugin.logger.warning("LeashPlayers: unknown fake entity type '$configured', falling back to BAT.")
            EntityTypes.BAT
        }
    }

    override fun createVisual(link: LeashLink) {
        syncViewers(link)
    }

    override fun updateVisual(link: LeashLink) {
        syncViewers(link)
        broadcastTeleport(link)
    }

    override fun removeVisual(link: LeashLink) {
        destroyForAll(link)
    }

    override fun resendVisualsTo(viewer: Player, links: Collection<LeashLink>) {
        for (link in links) {
            if (!shouldViewerSee(viewer, link)) continue
            spawnFor(viewer, link)
            link.viewers.add(viewer.uniqueId)
        }
    }

    override fun shutdown(links: Collection<LeashLink>) {
        for (link in links) {
            destroyForAll(link)
        }
    }

    fun generateEntityId(): Int = SpigotReflectionUtil.generateEntityId()

    private fun send(player: Player, wrapper: PacketWrapper<*>) {
        try {
            PacketEvents.getAPI().playerManager.sendPacket(player, wrapper)
        } catch (t: Throwable) {
            plugin.logger.fine("[LeashPlayers] Failed to send packet to ${player.name}: ${t.message}")
        }
    }

    private fun buildMetadata(): List<EntityData<*>> {
        @Suppress("UNCHECKED_CAST")
        return listOf(
            EntityData(0, EntityDataTypes.BYTE, ENTITY_FLAG_INVISIBLE) as EntityData<*>,
            EntityData(5, EntityDataTypes.BOOLEAN, true) as EntityData<*>
        )
    }

    private fun shouldViewerSee(viewer: Player, link: LeashLink): Boolean {
        val holderEntity = link.holder.resolveEntity() ?: return false
        val target = link.target() ?: return false
        if (viewer.world.uid != target.world.uid || holderEntity.world.uid != target.world.uid) return false
        if (visualRadius <= 0.0) return true
        val radiusSquared = visualRadius * visualRadius
        return viewer.location.distanceSquared(target.location) <= radiusSquared ||
            viewer.location.distanceSquared(holderEntity.location) <= radiusSquared
    }

    private fun currentViewers(link: LeashLink): Set<UUID> {
        val target = link.target() ?: return emptySet()
        val holderEntity = link.holder.resolveEntity() ?: return emptySet()
        if (holderEntity.world.uid != target.world.uid) return emptySet()

        return buildSet {
            for (viewer in target.world.players) {
                if (shouldViewerSee(viewer, link)) {
                    add(viewer.uniqueId)
                }
            }
            add(target.uniqueId)
            val holderPlayer = Bukkit.getPlayer(link.holder.uniqueId)
            if (holderPlayer != null) {
                add(holderPlayer.uniqueId)
            }
        }
    }

    private fun spawnFor(viewer: Player, link: LeashLink) {
        val target = link.target() ?: return
        val holderEntityId = link.holder.resolveEntityId() ?: return
        val location = target.location
        val spawnPacket = WrapperPlayServerSpawnEntity(
            link.fakeEntityId,
            Optional.of(link.fakeEntityUuid),
            fakeEntityType,
            location.toVector3d(),
            location.pitch,
            location.yaw,
            location.yaw,
            0,
            Optional.empty()
        )
        send(viewer, spawnPacket)
        send(viewer, WrapperPlayServerEntityMetadata(link.fakeEntityId, buildMetadata()))
        send(viewer, WrapperPlayServerAttachEntity(link.fakeEntityId, holderEntityId, true))
    }

    private fun destroyFor(viewer: Player, link: LeashLink) {
        send(viewer, WrapperPlayServerDestroyEntities(link.fakeEntityId))
    }

    private fun destroyForAll(link: LeashLink) {
        for (viewerId in link.viewers.toSet()) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            destroyFor(viewer, link)
        }
        link.viewers.clear()
    }

    private fun syncViewers(link: LeashLink) {
        val wanted = currentViewers(link)

        for (viewerId in wanted) {
            if (viewerId in link.viewers) continue
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            spawnFor(viewer, link)
        }

        for (viewerId in link.viewers.toSet()) {
            if (viewerId in wanted) continue
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            destroyFor(viewer, link)
        }

        link.viewers.clear()
        link.viewers.addAll(wanted)
    }

    private fun broadcastTeleport(link: LeashLink) {
        if (link.viewers.isEmpty()) return
        val target = link.target() ?: return
        val location = target.location
        val teleportPacket = WrapperPlayServerEntityTeleport(
            link.fakeEntityId,
            location.toVector3d(),
            location.yaw,
            location.pitch,
            false
        )
        for (viewerId in link.viewers) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            send(viewer, teleportPacket)
        }
    }

    private fun Location.toVector3d(): Vector3d = Vector3d(x, y, z)
}
