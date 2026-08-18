package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

class StreakListener(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Daily Login Streak"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("streak.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        Acore.instance.streakManager.loadOrInit(event.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        Acore.instance.streakManager.processPlayerLogin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        Acore.instance.streakManager.unload(event.player.uniqueId)
    }
}
