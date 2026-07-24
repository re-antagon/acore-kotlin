package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.fairplay.XaeroFairPlayManager
import org.antagon.acore.module.AcoreModule
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

class XaeroFairPlayListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val manager: XaeroFairPlayManager = Acore.instance.xaeroFairPlayManager,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Xaero's Minimap Fair-Play"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("xaeroFairPlay.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val bypassPermission = "acore.fairplay.bypass"
    private val delayTicks = 10L

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        handlePlayer(event.player)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        handlePlayer(event.player)
    }

    private fun handlePlayer(player: Player) {
        if (!configManager.getBoolean("xaeroFairPlay.enabled", true)) return
        if (player.hasPermission(bypassPermission)) return

        val modeStr = configManager.getString("xaeroFairPlay.mode", "FAIR-PLAY-NETHER")

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) {
                manager.sendFairPlayPacket(player, modeStr)
            }
        }, delayTicks)
    }
}
