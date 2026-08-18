package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Logger

class VillagerTransportListener(
    private val plugin: Acore = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Villager Transportation"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("villagerTransport.enabled", true)
    }

    override fun enable() {
        registerEvents(plugin)
    }

    private val logger: Logger = plugin.logger
    private val villagerDetectionRange: Int
    private val allowCamelTransport: Boolean
    private val allowLlamaTransport: Boolean
    private val teleportOnDismount: Boolean

    init {
        villagerDetectionRange = configManager.getInt("villagerTransport.detectionRange", 3)
        allowCamelTransport = configManager.getBoolean("villagerTransport.camel.enabled", true)
        allowLlamaTransport = configManager.getBoolean("villagerTransport.llama.enabled", true)
        teleportOnDismount = configManager.getBoolean("villagerTransport.teleportOnDismount", true)

        if (allowLlamaTransport) {
            startLlamaDetectionTask()
        }

        if (allowCamelTransport) {
            startCamelDetectionTask()
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        if (!allowCamelTransport) return

        if (event.entered is Player && event.vehicle is Camel) {
            val camel = event.vehicle as Camel
            object : BukkitRunnable() {
                override fun run() {
                    if (!camel.isValid || camel.passengers.size != 1) return

                    camel.getNearbyEntities(villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble()).stream()
                        .filter { entity -> entity is Villager }
                        .map { entity -> entity as Villager }
                        .findFirst()
                        .ifPresent { villager ->
                            if (villager.vehicle == null) {
                                camel.addPassenger(villager)
                                logger.info("Villager mounted on camel")
                            }
                        }
                }
            }.runTaskLater(plugin, 5L)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.rightClicked is Villager) {
            val villager = event.rightClicked as Villager
            val vehicle = villager.vehicle

            if (vehicle != null && (vehicle is Camel || vehicle is Llama ||
                                   vehicle is Boat || vehicle is Minecart)) {
                vehicle.removePassenger(villager)

                if (teleportOnDismount) {
                    villager.teleport(event.player.location)
                }

                event.isCancelled = true
                logger.info("Villager dismounted from " + vehicle.type.name)
            }
        }
    }

    private fun startCamelDetectionTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    val vehicle = player.vehicle
                    if (vehicle is Camel && vehicle.passengers.size == 1) {
                        vehicle.getNearbyEntities(villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble()).stream()
                            .filter { entity -> entity is Villager }
                            .map { entity -> entity as Villager }
                            .findFirst()
                            .ifPresent { villager ->
                                if (villager.vehicle == null) {
                                    vehicle.addPassenger(villager)
                                    logger.info("Villager mounted on camel (from detection task)")
                                }
                            }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun startLlamaDetectionTask() {
        object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    // Find llamas near the player within 32 blocks
                    val nearbyEntities = player.getNearbyEntities(32.0, 32.0, 32.0)
                    for (entity in nearbyEntities) {
                        if (entity is Llama) {
                            val llama = entity
                            if (hasCarpet(llama) && llama.passengers.isEmpty()) {
                                llama.getNearbyEntities(villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble()).stream()
                                    .filter { ent -> ent is Villager }
                                    .map { ent -> ent as Villager }
                                    .findFirst()
                                    .ifPresent { villager ->
                                        if (villager.vehicle == null) {
                                            llama.addPassenger(villager)
                                            logger.info("Villager mounted on llama")
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun hasCarpet(llama: Llama): Boolean {
        val decor = llama.inventory.decor ?: return false
        val material = decor.type
        return material.name.endsWith("_CARPET")
    }
}