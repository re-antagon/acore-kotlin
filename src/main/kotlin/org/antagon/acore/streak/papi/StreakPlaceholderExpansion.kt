package org.antagon.acore.streak.papi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.antagon.acore.Acore
import org.antagon.acore.streak.StreakManager
import org.bukkit.OfflinePlayer

class StreakPlaceholderExpansion(
    private val plugin: Acore,
    private val streakManager: StreakManager
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "acore"

    override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString(", ").ifEmpty { "re-antagon" }

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return ""
        val uuid = player.uniqueId
        val data = streakManager.getCachedData(uuid) ?: streakManager.loadOrInit(uuid)
        val today = streakManager.getEffectiveToday()

        return when (params.lowercase()) {
            "streak", "streak_current" -> data.currentStreak.toString()
            "streak_max", "streak_highest" -> data.highestStreak.toString()
            "streak_total", "streak_total_logins" -> data.totalLogins.toString()
            "streak_freezes" -> data.streakFreezes.toString()
            "streak_last_login" -> data.lastLoginDate?.toString() ?: "Never"
            "streak_time_until_reset" -> streakManager.getTimeUntilReset()
            "streak_is_active_today" -> (data.lastLoginDate == today).toString()
            else -> null
        }
    }
}
