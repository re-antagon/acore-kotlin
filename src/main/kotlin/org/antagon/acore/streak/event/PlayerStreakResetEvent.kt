package org.antagon.acore.streak.event

import org.antagon.acore.streak.PlayerStreakData
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class PlayerStreakResetEvent(
    val player: Player,
    val streakData: PlayerStreakData,
    val previousStreak: Int,
    val newStreak: Int
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
