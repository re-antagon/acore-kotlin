package org.antagon.acore.feature.grab

import org.bukkit.World
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// active physics-gun grab session
// val viewers - runtime extension of the spec model: players that currently
// see the fake vehicle entity (used to diff spawn/destroy packets)
data class GrabSession(
    val holderId: UUID,
    val heldId: UUID,
    val fakeEntityId: Int,
    var currentPos: Vector,
    var distance: Double,
    val world: World,
    @Volatile var lastTickTime: Long,
    val viewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
    // non-null while the held player is offline but kept captured (persistent-held)
    @Volatile var heldOfflineSince: Long? = null,
    var ghostEntityId: Int? = null,
    var ghostUuid: UUID? = null,
    var textDisplayEntityId: Int? = null,
    var cachedTextures: Pair<String, String>? = null,
    var cachedName: String = "",
    var glowingPulseWhite: Boolean = true
)

