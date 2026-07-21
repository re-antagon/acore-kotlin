package org.antagon.acore.fairplay

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
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

class XaeroFairPlayManager(private val plugin: JavaPlugin) {
    private val logger: Logger = plugin.logger
    private var usePacketEvents = false
    private var isLoaded = false

    private fun isPacketEventsAvailable(): Boolean {
        return try {
            Bukkit.getPluginManager().isPluginEnabled("PacketEvents")
        } catch (e: Throwable) {
            false
        }
    }

    fun onLoad() {
        if (isPacketEventsAvailable()) {
            try {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin))
                PacketEvents.getAPI().load()
                isLoaded = true
                logger.info("XaeroFairPlay: PacketEvents API loaded.")
            } catch (e: Throwable) {
                isLoaded = false
                logger.warning("XaeroFairPlay: Could not load PacketEvents API (${e.message}). Falling back to Bukkit message sending.")
            }
        } else {
            logger.warning("XaeroFairPlay: PacketEvents plugin is not installed on server. Will use Bukkit fallback message sending.")
        }
    }

    fun init() {
        if (isPacketEventsAvailable() && isLoaded) {
            try {
                PacketEvents.getAPI().init()
                usePacketEvents = true
                logger.info("XaeroFairPlay: PacketEvents API initialized successfully.")
            } catch (e: Throwable) {
                usePacketEvents = false
                logger.warning("XaeroFairPlay: Could not init PacketEvents API (${e.message}). Falling back to Bukkit message sending.")
            }
        } else {
            usePacketEvents = false
        }
    }

    fun terminate() {
        if (usePacketEvents) {
            try {
                PacketEvents.getAPI().terminate()
            } catch (e: Throwable) {
                // Ignore errors during plugin shutdown
            }
        }
    }

    fun sendFairPlayPacket(player: Player, modeStr: String) {
        val mode = FairPlayMode.fromString(modeStr, logger)
        val code = mode.code ?: return

        if (usePacketEvents) {
            try {
                val protocolManager = PacketEvents.getAPI().protocolManager
                val packet = WrapperPlayServerSystemChatMessage(false, Component.text(code))
                protocolManager.sendPacketSilently(protocolManager.getChannel(player.uniqueId), packet)
                return
            } catch (e: Throwable) {
                logger.warning("XaeroFairPlay: Failed sending packet via PacketEvents to ${player.name} (${e.message}). Falling back to Bukkit message.")
            }
        }

        // Fallback using Bukkit / Adventure player.sendMessage
        try {
            player.sendMessage(Component.text(code))
        } catch (e: Throwable) {
            logger.severe("XaeroFairPlay: Error sending fallback message to ${player.name}: ${e.message}")
        }
    }
}
