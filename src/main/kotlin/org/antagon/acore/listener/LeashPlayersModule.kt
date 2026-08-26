package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.listener.leash.LeashManager
import org.antagon.acore.listener.leash.LeashVisualManager
import org.antagon.acore.listener.leash.NoopLeashVisualManager
import org.antagon.acore.listener.leash.PacketEventsLeashVisualManager
import org.antagon.acore.util.DependencyHandler
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

// lets players tie other players with a lead item
class LeashPlayersModule(
    private val plugin: Plugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Leash Players"

    private lateinit var visualManager: LeashVisualManager
    private lateinit var leashManager: LeashManager
    private var tickTask: BukkitTask? = null

    private val updateIntervalTicks: Long
        get() = configManager.getInt("leashPlayers.leash.update-interval-ticks", 1).coerceAtLeast(1).toLong()

    override fun shouldEnable(): Boolean = configManager.getBoolean("leashPlayers.enabled", true)

    override fun enable() {
        visualManager = createVisualManager()
        leashManager = LeashManager(plugin, configManager, visualManager)
        registerEvents(plugin)
        startTickTask()
    }

    override fun disable() {
        tickTask?.cancel()
        tickTask = null
        if (::leashManager.isInitialized) {
            leashManager.clearAll()
        }
        super.disable()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val holder = event.player
        val target = event.rightClicked as? org.bukkit.entity.Player ?: return

        if (leashManager.tryManualUnleash(holder, target)) {
            event.isCancelled = true
            return
        }

        if (leashManager.tryLeash(holder, target)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        leashManager.handleQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        leashManager.handleDeath(event.entity)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        leashManager.handleWorldChange(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (event.player.isOnline) {
                leashManager.syncVisualsForViewer(event.player)
            }
        }, 5L)
    }

    private fun startTickTask() {
        tickTask?.cancel()
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            leashManager.update()
        }, updateIntervalTicks, updateIntervalTicks)
    }

    private fun createVisualManager(): LeashVisualManager {
        if (!DependencyHandler.isPluginEnabled("PacketEvents")) {
            plugin.logger.warning("LeashPlayers: PacketEvents not found. Visual rope disabled, physics-only mode enabled.")
            return NoopLeashVisualManager()
        }

        val packetEventsPlugin = Bukkit.getPluginManager().getPlugin("PacketEvents")
            ?: Bukkit.getPluginManager().getPlugin("packetevents")
        plugin.logger.info("LeashPlayers: PacketEvents version detected: ${packetEventsPlugin?.pluginMeta?.version ?: "unknown"}")

        return try {
            PacketEventsLeashVisualManager(plugin, configManager)
        } catch (t: Throwable) {
            plugin.logger.warning("LeashPlayers: failed to initialize PacketEvents visuals: ${t.message}")
            NoopLeashVisualManager()
        }
    }
}
