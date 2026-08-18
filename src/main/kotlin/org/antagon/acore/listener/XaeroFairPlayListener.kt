package org.antagon.acore.listener

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import net.kyori.adventure.text.Component
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.antagon.acore.util.DependencyHandler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

enum class FairPlayMode(val code: String?) {
    FAIR_PLAY_NETHER("§f§a§i§r§x§a§e§r§o§x§a§e§r§o§w§m§n§e§t§h§e§r§i§s§f§a§i§r"),
    FAIR_PLAY("§f§a§i§r§x§a§e§r§o"),
    FULL_DISABLE("§n§o§m§i§n§i§m§a§p"),
    NONE(null);

    companion object {
        fun fromString(modeStr: String, logger: Logger): FairPlayMode {
            val formatted = modeStr.uppercase().trim()
            return when (formatted) {
                "FAIR-PLAY-NETHER", "FAIR_PLAY_NETHER" -> FAIR_PLAY_NETHER
                "FAIR-PLAY", "FAIR_PLAY" -> FAIR_PLAY
                "FULL-DISABLE", "FULL_DISABLE" -> FULL_DISABLE
                "NONE" -> NONE
                else -> {
                    logger.warning("Invalid xaeroFairPlay mode '$modeStr' in config.yml. Defaulting to FAIR-PLAY-NETHER.")
                    FAIR_PLAY_NETHER
                }
            }
        }
    }
}

class XaeroFairPlayListener(
    private val plugin: JavaPlugin = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Xaero's Minimap Fair-Play"

    private val bypassPermission = "acore.fairplay.bypass"
    private val delayTicks = 10L
    private var usePacketEvents = false

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("xaeroFairPlay.enabled", true)
    }

    override fun enable() {
        usePacketEvents = DependencyHandler.isPluginEnabled("PacketEvents")
        if (usePacketEvents) {
            plugin.logger.info("XaeroFairPlay: PacketEvents integration enabled.")
        } else {
            plugin.logger.info("XaeroFairPlay: PacketEvents not found. Using Bukkit message fallback.")
        }

        registerEvents(plugin)
    }

    override fun disable() {
        super.disable()
        usePacketEvents = false
    }

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
                sendFairPlayPacket(player, modeStr)
            }
        }, delayTicks)
    }

    private fun sendFairPlayPacket(player: Player, modeStr: String) {
        val mode = FairPlayMode.fromString(modeStr, plugin.logger)
        val code = mode.code ?: return

        if (usePacketEvents) {
            val sent = DependencyHandler.executeSafely(
                dependencyName = "PacketEvents",
                featureName = "Send FairPlay Packet",
                fallback = false,
                logger = plugin.logger
            ) {
                val protocolManager = PacketEvents.getAPI().protocolManager
                val packet = WrapperPlayServerSystemChatMessage(false, Component.text(code))
                protocolManager.sendPacketSilently(protocolManager.getChannel(player.uniqueId), packet)
                true
            } ?: false

            if (sent) return
        }

        // Fallback using Bukkit / Adventure player.sendMessage
        try {
            player.sendMessage(Component.text(code))
        } catch (e: Throwable) {
            plugin.logger.severe("XaeroFairPlay: Error sending fallback message to ${player.name}: ${e.message}")
        }
    }
}
