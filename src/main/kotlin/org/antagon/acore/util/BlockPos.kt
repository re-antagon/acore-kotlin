package org.antagon.acore.util

import org.bukkit.Location

data class BlockPos(
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int
) {
    fun isWithinRadius(other: BlockPos, radius: Int): Boolean {
        if (worldName != other.worldName) return false
        val dx = (x - other.x).toLong()
        val dy = (y - other.y).toLong()
        val dz = (z - other.z).toLong()
        val r = radius.toLong()
        return (dx * dx + dy * dy + dz * dz) <= (r * r)
    }

    companion object {
        fun from(location: Location): BlockPos {
            return BlockPos(
                location.world?.name ?: "",
                location.blockX,
                location.blockY,
                location.blockZ
            )
        }
    }
}
