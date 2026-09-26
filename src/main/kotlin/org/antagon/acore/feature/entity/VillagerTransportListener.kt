package org.antagon.acore.feature.entity

import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.core.AcoreModule
import org.bukkit.Chunk
import org.bukkit.Tag
import org.bukkit.entity.Camel
import org.bukkit.entity.Entity
import org.bukkit.entity.Llama
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Logger
import kotlin.math.abs

class VillagerTransportListener(
    private val plugin: Acore = Acore.instance,
    private val configManager: ConfigManager = ConfigManager.getInstance()
) : AcoreModule, Listener {

    override val name: String = "Villager Transportation"

    override fun shouldEnable(): Boolean {
        return configManager.getBoolean("villagerTransport.enabled", true)
    }

    private var camelTask: BukkitTask? = null
    private var llamaTask: BukkitTask? = null

    private val logger: Logger = plugin.logger
    private val villagerDetectionRange: Int
    private val allowCamelTransport: Boolean
    private val allowLlamaTransport: Boolean
    private val teleportOnDismount: Boolean

    private val knownLlamas = HashSet<Llama>()

    init {
        villagerDetectionRange = configManager.getInt("villagerTransport.detectionRange", 3)
        allowCamelTransport = configManager.getBoolean("villagerTransport.camel.enabled", true)
        allowLlamaTransport = configManager.getBoolean("villagerTransport.llama.enabled", true)
        teleportOnDismount = configManager.getBoolean("villagerTransport.teleportOnDismount", true)
    }

    override fun enable() {
        registerEvents(plugin)
        if (allowCamelTransport) {
            startCamelDetectionTask()
        }
        if (allowLlamaTransport) {
            seedRegistryFromLoadedChunks()
            startLlamaDetectionTask()
        }
    }

    override fun disable() {
        super.disable()
        camelTask?.cancel()
        camelTask = null
        llamaTask?.cancel()
        llamaTask = null
        knownLlamas.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        if (!allowCamelTransport) return

        if (event.entered is Player && event.vehicle is Camel) {
            val camel = event.vehicle as Camel
            object : BukkitRunnable() {
                override fun run() {
                    if (!camel.isValid || camel.passengers.size != 1) return
                    mountNearbyVillager(camel, "camel")
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
                                   vehicle is org.bukkit.entity.Boat || vehicle is org.bukkit.entity.Minecart)) {
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
        camelTask = object : BukkitRunnable() {
            override fun run() {
                for (player in plugin.server.onlinePlayers) {
                    val vehicle = player.vehicle
                    if (vehicle is Camel && vehicle.passengers.size == 1) {
                        mountNearbyVillager(vehicle, "camel (from detection task)")
                    }
                }
            }
        }.runTaskTimer(plugin, TASK_INTERVAL_TICKS, TASK_INTERVAL_TICKS)
    }

    private fun startLlamaDetectionTask() {
        llamaTask = object : BukkitRunnable() {
            override fun run() {
                processLlamaRegistry()
            }
        }.runTaskTimer(plugin, TASK_INTERVAL_TICKS, TASK_INTERVAL_TICKS)
    }

    private fun seedRegistryFromLoadedChunks() {
        for (world in plugin.server.worlds) {
            for (chunk in world.loadedChunks) {
                indexChunk(chunk)
            }
        }
    }

    private fun indexChunk(chunk: Chunk) {
        for (entity in chunk.entities) {
            if (entity is Llama) {
                knownLlamas.add(entity)
            }
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!allowLlamaTransport) return
        indexChunk(event.chunk)
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (!allowLlamaTransport || knownLlamas.isEmpty()) return

        for (entity in event.chunk.entities) {
            if (entity is Llama) {
                knownLlamas.remove(entity)
            }
        }
    }

    @EventHandler
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (!allowLlamaTransport) return
        if (event.entity is Llama) {
            knownLlamas.add(event.entity as Llama)
        }
    }

    private fun processLlamaRegistry() {
        if (knownLlamas.isEmpty()) return

        val iterator = knownLlamas.iterator()
        while (iterator.hasNext()) {
            val llama = iterator.next()

            if (!llama.isValid) {
                iterator.remove()
                continue
            }

            if (llama.passengers.isNotEmpty()) continue
            if (!hasCarpet(llama)) continue
            if (!isPlayerNearby(llama)) continue
            if (!isChunkLoaded(llama)) {
                iterator.remove()
                continue
            }

            mountNearbyVillager(llama, "llama")
        }
    }

    private fun isChunkLoaded(llama: Llama): Boolean {
        val location = llama.location
        return location.world.isChunkLoaded(chunkIndexOf(location.blockX), chunkIndexOf(location.blockZ))
    }

    private fun isPlayerNearby(llama: Llama): Boolean {
        val llamaLocation = llama.location
        for (player in plugin.server.onlinePlayers) {
            if (player.world !== llamaLocation.world) continue
            if (abs(player.x - llamaLocation.x) > LLAMA_PLAYER_RANGE_BLOCKS) continue
            if (abs(player.y - llamaLocation.y) > LLAMA_PLAYER_RANGE_BLOCKS) continue
            if (abs(player.z - llamaLocation.z) > LLAMA_PLAYER_RANGE_BLOCKS) continue
            return true
        }
        return false
    }

    private fun hasCarpet(llama: Llama): Boolean {
        return llama.inventory.decor?.let { Tag.WOOL_CARPETS.isTagged(it.type) } ?: false
    }

    private fun mountNearbyVillager(vehicle: Entity, label: String) {
        val range = villagerDetectionRange.toDouble()
        for (entity in vehicle.getNearbyEntities(range, range, range)) {
            if (entity !is Villager) continue
            if (entity.vehicle != null) continue
            if (!vehicle.addPassenger(entity)) continue
            logger.info("Villager mounted on $label")
            return
        }
    }

    companion object {
        private const val TASK_INTERVAL_TICKS = 20L
        private const val LLAMA_PLAYER_RANGE_BLOCKS = 32.0
        private const val CHUNK_SHIFT = 4
        fun chunkIndexOf(blockCoord: Int): Int = blockCoord shr CHUNK_SHIFT
    }
}