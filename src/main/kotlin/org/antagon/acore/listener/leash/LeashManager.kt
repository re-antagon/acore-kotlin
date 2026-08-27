package org.antagon.acore.listener.leash

import org.antagon.acore.core.ConfigManager
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID

// stores active leash connections and applies movement restrictions
class LeashManager(
    private val plugin: Plugin,
    private val configManager: ConfigManager,
    private val visualManager: LeashVisualManager
) {
    private val linksByTarget = linkedMapOf<UUID, LeashLink>()
    private val targetsByHolder = hashMapOf<UUID, MutableSet<UUID>>()

    private val targetQuitReturnsLead: Boolean
        get() = configManager.getBoolean("leashPlayers.leash.return-lead-on-target-quit", true)

    private val leashLength: Double
        get() = configManager.getDouble("leashPlayers.leash.leash-length", 5.0)

    private val maxDistance: Double
        get() = configManager.getDouble("leashPlayers.leash.max-distance", 10.0)

    private val pullStrengthMultiplier: Double
        get() = configManager.getDouble("leashPlayers.leash.pull-strength-multiplier", 0.15)

    private val maxPullVelocity: Double
        get() = configManager.getDouble("leashPlayers.leash.max-pull-velocity", 0.5)

    private val leashItemRange: Double
        get() = configManager.getDouble("leashPlayers.leash.leash-item-range", 8.0)

    private val requirePermission: Boolean
        get() = configManager.getBoolean("leashPlayers.leash.require-permission", false)

    fun activeLinks(): List<LeashLink> = linksByTarget.values.toList()

    fun isLeashed(playerId: UUID): Boolean = linksByTarget.containsKey(playerId)

    fun isHolding(playerId: UUID): Boolean = targetsByHolder[playerId]?.isNotEmpty() == true

    fun syncVisualsForViewer(viewer: Player) {
        visualManager.resendVisualsTo(viewer, linksByTarget.values)
    }

    fun tryLeash(holder: Player, target: Player): Boolean {
        if (requirePermission && !holder.hasPermission("acore.leash.use")) return false
        if (holder.uniqueId == target.uniqueId) return false
        if (holder.inventory.itemInMainHand.type != Material.LEAD) return false
        if (target.gameMode == GameMode.CREATIVE || target.gameMode == GameMode.SPECTATOR) return false
        if (holder.world.uid != target.world.uid) return false
        if (holder.location.distanceSquared(target.location) > leashItemRange * leashItemRange) return false
        if (isLeashed(target.uniqueId)) return false
        if (isLeashed(holder.uniqueId)) return false
        if (isHolding(target.uniqueId)) return false

        val fakeEntityId = (visualManager as? PacketEventsLeashVisualManager)?.generateEntityId() ?: -1
        val link = LeashLink(
            holder = PlayerLeashHolder(holder.uniqueId),
            targetId = target.uniqueId,
            fakeEntityId = fakeEntityId
        )

        linksByTarget[target.uniqueId] = link
        targetsByHolder.computeIfAbsent(holder.uniqueId) { linkedSetOf() }.add(target.uniqueId)
        consumeLead(holder)
        visualManager.createVisual(link)
        playPlaceSound(holder)
        playPlaceSound(target)
        return true
    }

    fun tryManualUnleash(holder: Player, target: Player): Boolean {
        val link = linksByTarget[target.uniqueId] ?: return false
        if (link.holder.uniqueId != holder.uniqueId) return false
        unleashInternal(link, ReleaseMode.NORMAL, returnLead = true)
        return true
    }

    fun handleQuit(player: Player) {
        releaseHolderTargets(player.uniqueId, ReleaseMode.QUIET, returnLead = false)

        val link = linksByTarget[player.uniqueId] ?: return
        unleashInternal(link, ReleaseMode.QUIET, returnLead = targetQuitReturnsLead)
    }

    fun handleDeath(player: Player) {
        releaseHolderTargets(player.uniqueId, ReleaseMode.NORMAL, returnLead = false)

        val link = linksByTarget[player.uniqueId] ?: return
        unleashInternal(link, ReleaseMode.NORMAL, returnLead = true)
    }

    fun handleWorldChange(player: Player) {
        releaseHolderTargets(player.uniqueId, ReleaseMode.BROKEN, returnLead = false)

        val link = linksByTarget[player.uniqueId] ?: return
        unleashInternal(link, ReleaseMode.BROKEN, returnLead = false)
    }

    fun update() {
        if (linksByTarget.isEmpty()) return

        for (link in linksByTarget.values.toList()) {
            val holderEntity = link.holder.resolveEntity()
            val target = link.target()

            if (holderEntity == null || target == null || !target.isOnline) {
                unleashInternal(link, ReleaseMode.QUIET, returnLead = false)
                continue
            }
            if (!holderEntity.isValid || holderEntity.isDead || target.isDead) {
                unleashInternal(link, ReleaseMode.QUIET, returnLead = false)
                continue
            }
            if (holderEntity.world.uid != target.world.uid) {
                unleashInternal(link, ReleaseMode.BROKEN, returnLead = false)
                continue
            }

            val distance = holderEntity.location.distance(target.location)
            if (distance > maxDistance) {
                unleashInternal(link, ReleaseMode.BROKEN, returnLead = false)
                continue
            }
            if (distance > leashLength) {
                pullTowardsHolder(target, holderEntity.location.toVector(), distance)
            }

            visualManager.updateVisual(link)
        }
    }

    fun clearAll() {
        for (link in linksByTarget.values.toList()) {
            unleashInternal(link, ReleaseMode.QUIET, returnLead = false)
        }
        visualManager.shutdown(emptyList())
    }

    private fun releaseHolderTargets(holderId: UUID, mode: ReleaseMode, returnLead: Boolean) {
        val targetIds = targetsByHolder[holderId]?.toSet() ?: return
        for (targetId in targetIds) {
            val link = linksByTarget[targetId] ?: continue
            unleashInternal(link, mode, returnLead)
        }
    }

    private fun pullTowardsHolder(target: Player, holderVector: Vector, distance: Double) {
        val direction = holderVector.subtract(target.location.toVector())
        if (direction.lengthSquared() <= 0.0001) return

        val exceed = distance - leashLength
        val pullSpeed = (exceed * pullStrengthMultiplier).coerceAtMost(maxPullVelocity)
        val pullVector = direction.normalize().multiply(pullSpeed)
        val combined = target.velocity.clone().add(pullVector)
        val limited = if (combined.lengthSquared() > maxPullVelocity * maxPullVelocity) {
            combined.normalize().multiply(maxPullVelocity)
        } else {
            combined
        }
        target.velocity = limited
    }

    private fun consumeLead(holder: Player) {
        if (holder.gameMode == GameMode.CREATIVE) return
        val item = holder.inventory.itemInMainHand
        if (item.type != Material.LEAD) return
        if (item.amount <= 1) {
            holder.inventory.setItemInMainHand(null)
        } else {
            item.amount = item.amount - 1
            holder.inventory.setItemInMainHand(item)
        }
    }

    private fun giveLeadBack(holder: Player) {
        if (holder.gameMode == GameMode.CREATIVE) return
        val leftovers = holder.inventory.addItem(ItemStack(Material.LEAD, 1))
        leftovers.values.forEach { leftover ->
            holder.world.dropItemNaturally(holder.location, leftover)
        }
    }

    private fun playPlaceSound(player: Player) {
        player.playSound(player.location, "entity.leash_knot.place", 1f, 1f)
    }

    private fun playBreakSound(player: Player) {
        player.playSound(player.location, "entity.leash_knot.break", 1f, 1f)
    }

    private fun unleashInternal(link: LeashLink, mode: ReleaseMode, returnLead: Boolean) {
        if (linksByTarget.remove(link.targetId) == null) return

        targetsByHolder[link.holder.uniqueId]?.let { targets ->
            targets.remove(link.targetId)
            if (targets.isEmpty()) {
                targetsByHolder.remove(link.holder.uniqueId)
            }
        }

        visualManager.removeVisual(link)

        val target = link.target()
        val holderPlayer = link.holder.resolveEntity() as? Player

        if (returnLead && holderPlayer != null && holderPlayer.isOnline) {
            giveLeadBack(holderPlayer)
        }

        when (mode) {
            ReleaseMode.NORMAL,
            ReleaseMode.BROKEN -> {
                if (holderPlayer != null && holderPlayer.isOnline) {
                    playBreakSound(holderPlayer)
                }
                if (target != null && target.isOnline) {
                    playBreakSound(target)
                }
            }
            ReleaseMode.QUIET -> Unit
        }
    }

    private enum class ReleaseMode {
        NORMAL,
        BROKEN,
        QUIET
    }
}
