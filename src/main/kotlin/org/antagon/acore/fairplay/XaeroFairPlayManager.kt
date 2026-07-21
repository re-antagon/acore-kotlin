package org.antagon.acore.fairplay

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import net.kyori.adventure.text.Component
import org.antagon.acore.util.DependencyHandler
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
        return DependencyHandler.isPluginEnabled("PacketEvents")
    }

    fun onLoad() {
        if (isPacketEventsAvailable()) {
            val success = DependencyHandler.executeSafely(
                dependencyName = "PacketEvents",
                featureName = "PacketEvents API Load",
                fallback = false,
                logger = logger
            ) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin))
                PacketEvents.getAPI().load()
                true
            } ?: false

            isLoaded = success
            if (isLoaded) {
                logger.info("XaeroFairPlay: PacketEvents API loaded.")
            } else {
                logger.warning("XaeroFairPlay: Could not load PacketEvents API. Falling back to Bukkit message sending.")
            }
        } else {
            logger.warning("XaeroFairPlay: PacketEvents plugin is not installed on server. Will use Bukkit fallback message sending.")
        }
    }

    fun init() {
        if (isPacketEventsAvailable() && isLoaded) {
            val success = DependencyHandler.executeSafely(
                dependencyName = "PacketEvents",
                featureName = "PacketEvents API Init",
                fallback = false,
                logger = logger
            ) {
                PacketEvents.getAPI().init()
                true
            } ?: false

            usePacketEvents = success
            if (usePacketEvents) {
                logger.info("XaeroFairPlay: PacketEvents API initialized successfully.")
            } else {
                logger.warning("XaeroFairPlay: Could not init PacketEvents API. Falling back to Bukkit message sending.")
            }
        } else {
            usePacketEvents = false
        }
    }

    fun terminate() {
        if (usePacketEvents) {
            DependencyHandler.executeSafely(
                dependencyName = "PacketEvents",
                featureName = "PacketEvents API Terminate",
                fallback = null,
                logger = logger
            ) {
                PacketEvents.getAPI().terminate()
            }
        }
    }

    fun sendFairPlayPacket(player: Player, modeStr: String) {
        val mode = FairPlayMode.fromString(modeStr, logger)
        val code = mode.code ?: return

        if (usePacketEvents) {
            val sent = DependencyHandler.executeSafely(
                dependencyName = "PacketEvents",
                featureName = "Send FairPlay Packet",
                fallback = false,
                logger = logger
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
            logger.severe("XaeroFairPlay: Error sending fallback message to ${player.name}: ${e.message}")
        }
    }
}
