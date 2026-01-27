package org.antagon.acore.listener

import org.antagon.acore.Acore
import org.antagon.acore.api.IConfig
import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import java.util.logging.Logger

class VillagerTransportListener(private val plugin: Acore, private val config: IConfig) : Listener {
    private val logger: Logger = plugin.logger
    private val villagerDetectionRange: Int
    private val allowCamelTransport: Boolean
    private val allowLlamaTransport: Boolean
    private val teleportOnDismount: Boolean

    init {
        villagerDetectionRange = config.getInt("villagerTransport.detectionRange", 3)
        allowCamelTransport = config.getBoolean("villagerTransport.camel.enabled", true)
        allowLlamaTransport = config.getBoolean("villagerTransport.llama.enabled", true)
        teleportOnDismount = config.getBoolean("villagerTransport.teleportOnDismount", true)

        if (allowLlamaTransport) {
            startLlamaDetectionTask()
        }

        if (allowCamelTransport) {
            startCamelDetectionTask()
        }
    }

    @EventHandler
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

    @EventHandler
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
                plugin.server.worlds.forEach { world ->
                    world.getEntitiesByClass(Camel::class.java).forEach { camel ->
                        if (camel.passengers.size == 1 &&
                            camel.passengers[0] is Player) {

                            camel.getNearbyEntities(villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble()).stream()
                                .filter { entity -> entity is Villager }
                                .map { entity -> entity as Villager }
                                .findFirst()
                                .ifPresent { villager ->
                                    if (villager.vehicle == null) {
                                        camel.addPassenger(villager)
                                        logger.info("Villager mounted on camel (from detection task)")
                                    }
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
                plugin.server.worlds.forEach { world ->
                    world.getEntitiesByClass(Llama::class.java).forEach { llama ->
                        if (hasCarpet(llama) && llama.passengers.isEmpty()) {
                            llama.getNearbyEntities(villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble(), villagerDetectionRange.toDouble()).stream()
                                .filter { entity -> entity is Villager }
                                .map { entity -> entity as Villager }
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
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun hasCarpet(llama: Llama): Boolean {
        val decor = llama.inventory.decor ?: return false
        val material = decor.type
        return material.name.endsWith("_CARPET")
    }
}