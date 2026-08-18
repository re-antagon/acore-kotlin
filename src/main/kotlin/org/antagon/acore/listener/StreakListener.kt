package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.streak.StreakManager
import org.antagon.acore.streak.papi.StreakPlaceholderExpansion
import org.antagon.acore.util.DependencyHandler
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin

class StreakListener(
    private val plugin: Plugin = Acore.instance,
    private val streakManager: StreakManager = Acore.instance.streakManager,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Daily Login Streak"

    private var expansion: StreakPlaceholderExpansion? = null

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("streak.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)

        DependencyHandler.executeSafely("PlaceholderAPI", "Streak Placeholders") {
            expansion = StreakPlaceholderExpansion(plugin as Acore, streakManager).apply {
                register()
            }
        }
    }

    override fun disable() {
        super.disable()
        expansion?.unregister()
        expansion = null
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
