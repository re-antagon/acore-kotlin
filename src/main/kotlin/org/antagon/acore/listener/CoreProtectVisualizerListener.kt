package org.antagon.acore.listener

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import net.coreprotect.CoreProtect
import net.coreprotect.CoreProtectAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.antagon.acore.Acore
import org.antagon.acore.core.ConfigManager
import org.antagon.acore.module.AcoreModule
import org.antagon.acore.util.DependencyHandler
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-file implementation for CoreProtect 3D Phantom Visualization.
 */
class CoreProtectVisualizerListener : AcoreModule, Listener {

    override val name: String = "CoreProtect Visualizer"

    override fun shouldEnable(): Boolean {
        return DependencyHandler.isPluginEnabled("CoreProtect") &&
               DependencyHandler.isPluginEnabled("PacketEvents") &&
               ConfigManager.getInstance().getBoolean("visualize.enabled", true)
    }

    override fun enable() {
        registerEvents(Acore.instance)
        instance = this
    }

    override fun disable() {
        super.disable()
        SessionManager.clearAllSessions()
    }

    companion object {
        var instance: CoreProtectVisualizerListener? = null
            private set
    }

    // ==========================================
    // 1. DATA MODELS & ARGUMENT PARSER
    // ==========================================

    data class LookupQuery(
        val actions: List<Int>?,
        val includeBlocks: List<String>?,
        val excludeBlocks: List<String>?,
        val users: List<String>?,
        val timeSeconds: Int,
        val radius: Int,
        val durationSeconds: Int
    )

    object Parser {
        fun parseArgs(rawArgs: String, defaultConfig: ConfigManager): LookupQuery {
            val tokens = rawArgs.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

            val actions = mutableListOf<Int>()
            val includeBlocks = mutableListOf<String>()
            val excludeBlocks = mutableListOf<String>()
            val users = mutableListOf<String>()
            var timeSeconds = 3600 // Default 1 hour
            var radius = 20        // Default radius 20
            var durationSeconds = defaultConfig.getInt("visualize.default-duration", 30)

            for (token in tokens) {
                val parts = token.split(":", limit = 2)
                if (parts.size < 2) continue
                val key = parts[0].lowercase()
                val valStr = parts[1]

                when (key) {
                    "a", "action" -> {
                        when (valStr.lowercase()) {
                            "-block" -> actions.add(0)
                            "+block" -> actions.add(1)
                            "block" -> { actions.add(0); actions.add(1) }
                            "click", "interact" -> actions.add(2)
                            "container" -> actions.add(3)
                            "kill" -> actions.add(4)
                            "item", "drop", "pickup" -> actions.add(5)
                        }
                    }
                    "i", "include", "b", "block" -> {
                        includeBlocks.addAll(valStr.split(","))
                    }
                    "e", "exclude" -> {
                        excludeBlocks.addAll(valStr.split(","))
                    }
                    "u", "user" -> {
                        users.addAll(valStr.split(","))
                    }
                    "t", "time" -> {
                        timeSeconds = parseTimeString(valStr, 3600)
                    }
                    "r", "radius" -> {
                        radius = valStr.toIntOrNull() ?: 20
                    }
                    "d", "duration" -> {
                        durationSeconds = parseTimeString(valStr, 30)
                    }
                }
            }

            return LookupQuery(
                actions = actions.ifEmpty { null },
                includeBlocks = includeBlocks.ifEmpty { null },
                excludeBlocks = excludeBlocks.ifEmpty { null },
                users = users.ifEmpty { null },
                timeSeconds = timeSeconds,
                radius = radius,
                durationSeconds = durationSeconds
            )
        }

        private fun parseTimeString(str: String, defaultSec: Int): Int {
            var total = 0
            val regex = "(\\d+)([smhd])".toRegex(RegexOption.IGNORE_CASE)
            val matches = regex.findAll(str)
            if (!matches.any()) {
                val directInt = str.toIntOrNull()
                return if (directInt != null) directInt * 60 else defaultSec
            }
            for (match in matches) {
                val num = match.groupValues[1].toIntOrNull() ?: 0
                val unit = match.groupValues[2].lowercase()
                total += when (unit) {
                    "s" -> num
                    "m" -> num * 60
                    "h" -> num * 3600
                    "d" -> num * 86400
                    else -> 0
                }
            }
            return if (total > 0) total else defaultSec
        }
    }

    // ==========================================
    // 2. PHANTOM SESSION & PACKET HANDLER
    // ==========================================

    class PhantomSession(
        val player: Player,
        val durationSeconds: Int
    ) {
        val spawnedEntityIds = mutableListOf<Int>()
        val teamNames = mutableSetOf<String>()
        var expireTask: BukkitTask? = null

        fun spawnPhantomObjects(results: List<CoreProtectAPI.ParseResult>, configManager: ConfigManager) {
            val protocolManager = PacketEvents.getAPI().protocolManager
            val user = protocolManager.getUser(player) ?: return

            val maxLimit = configManager.getInt("visualize.max-blocks-limit", 500)
            val showHolograms = configManager.getBoolean("visualize.show-holograms", true)
            val displayMode = configManager.getString("visualize.display-mode", "exact_object")

            // Register Glow Teams
            setupGlowTeams(user)

            val displayResults = results.take(maxLimit)
            for (result in displayResults) {
                val loc = Location(player.world, result.x.toDouble(), result.y.toDouble(), result.z.toDouble())
                val actionId = result.actionId
                val rawMaterial = result.type ?: Material.STONE

                val teamColorName = when (actionId) {
                    0 -> configManager.getString("visualize.colors.removed", "RED")
                    1 -> configManager.getString("visualize.colors.placed", "GREEN")
                    2 -> configManager.getString("visualize.colors.click", "YELLOW")
                    3 -> configManager.getString("visualize.colors.container", "GOLD")
                    4 -> configManager.getString("visualize.colors.kill", "DARK_PURPLE")
                    else -> configManager.getString("visualize.colors.item", "AQUA")
                }
                val teamName = "acore_viz_${teamColorName.lowercase()}"
                val glowColor = parseColor(teamColorName)

                val entityId = EntityIdGenerator.nextId()
                val uuid = UUID.randomUUID()
                spawnedEntityIds.add(entityId)

                // 1. Spawn BlockDisplay / ItemDisplay
                val materialToRender = if (displayMode == "exact_object") {
                    rawMaterial
                } else {
                    when (actionId) {
                        0 -> Material.RED_CONCRETE
                        1 -> Material.GREEN_CONCRETE
                        else -> Material.YELLOW_STAINED_GLASS
                    }
                }

                if (actionId == 5 || (!materialToRender.isBlock && materialToRender.isItem)) {
                    spawnItemDisplay(user, entityId, uuid, loc, materialToRender, teamName)
                } else {
                    spawnBlockDisplay(user, entityId, uuid, loc, materialToRender, teamName)
                }

                // 2. Optional Hologram Label
                if (showHolograms) {
                    val holoEntityId = EntityIdGenerator.nextId()
                    val holoUuid = UUID.randomUUID()
                    spawnedEntityIds.add(holoEntityId)
                    val actionName = when (actionId) {
                        0 -> "Сломано"
                        1 -> "Поставлено"
                        2 -> "Клик"
                        3 -> "Контейнер"
                        4 -> "Убийство"
                        else -> "Предмет"
                    }
                    val timeAgoStr = formatTimeAgo(result.timestamp)
                    val label = "§e[${materialToRender.name}] §f$actionName: §a${result.player} §7($timeAgoStr)"
                    spawnTextHologram(user, holoEntityId, holoUuid, loc.clone().add(0.5, 1.1, 0.5), label)
                }
            }

            // Schedule auto-despawn
            expireTask = Bukkit.getScheduler().runTaskLater(Acore.instance, Runnable {
                despawn()
            }, durationSeconds * 20L)
        }

        private fun spawnBlockDisplay(user: User, entityId: Int, uuid: UUID, loc: Location, mat: Material, teamName: String) {
            val spawnPacket = WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.BLOCK_DISPLAY,
                Vector3d(loc.x, loc.y, loc.z),
                0f, 0f, 0f, 0, null
            )
            user.sendPacketSilently(spawnPacket)

            // Entity Metadata: Glowing = 0x40
            val metadataList = mutableListOf<EntityData<*>>()
            metadataList.add(EntityData(0, EntityDataTypes.BYTE, (0x40).toByte()))
            val metadataPacket = WrapperPlayServerEntityMetadata(entityId, metadataList)
            user.sendPacketSilently(metadataPacket)

            // Add entity UUID to scoreboard team for colored glow
            addEntityToTeam(user, teamName, uuid.toString())
        }

        private fun spawnItemDisplay(user: User, entityId: Int, uuid: UUID, loc: Location, mat: Material, teamName: String) {
            val spawnPacket = WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.ITEM_DISPLAY,
                Vector3d(loc.x + 0.5, loc.y + 0.5, loc.z + 0.5),
                0f, 0f, 0f, 0, null
            )
            user.sendPacketSilently(spawnPacket)

            val metadataList = mutableListOf<EntityData<*>>()
            metadataList.add(EntityData(0, EntityDataTypes.BYTE, (0x40).toByte()))
            val metadataPacket = WrapperPlayServerEntityMetadata(entityId, metadataList)
            user.sendPacketSilently(metadataPacket)

            addEntityToTeam(user, teamName, uuid.toString())
        }

        private fun spawnTextHologram(user: User, entityId: Int, uuid: UUID, loc: Location, label: String) {
            val spawnPacket = WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.TEXT_DISPLAY,
                Vector3d(loc.x, loc.y, loc.z),
                0f, 0f, 0f, 0, null
            )
            user.sendPacketSilently(spawnPacket)

            val metadataList = mutableListOf<EntityData<*>>()
            metadataList.add(EntityData(23, EntityDataTypes.ADV_COMPONENT, Component.text(label)))
            val metadataPacket = WrapperPlayServerEntityMetadata(entityId, metadataList)
            user.sendPacketSilently(metadataPacket)
        }

        private fun setupGlowTeams(user: User) {
            val colors = listOf("RED", "GREEN", "YELLOW", "GOLD", "DARK_PURPLE", "AQUA")
            for (colorName in colors) {
                val teamName = "acore_viz_${colorName.lowercase()}"
                if (teamNames.add(teamName)) {
                    val teamInfo = WrapperPlayServerTeams.ScoreBoardTeamInfo(
                        Component.text(teamName),
                        Component.empty(),
                        Component.empty(),
                        WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                        WrapperPlayServerTeams.CollisionRule.NEVER,
                        parseColor(colorName),
                        WrapperPlayServerTeams.OptionData.NONE
                    )
                    val createTeamPacket = WrapperPlayServerTeams(
                        teamName,
                        WrapperPlayServerTeams.TeamMode.CREATE,
                        Optional.of(teamInfo),
                        emptyList()
                    )
                    user.sendPacketSilently(createTeamPacket)
                }
            }
        }

        private fun addEntityToTeam(user: User, teamName: String, entityUuidStr: String) {
            val addMemberPacket = WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                Optional.empty<WrapperPlayServerTeams.ScoreBoardTeamInfo>(),
                listOf(entityUuidStr)
            )
            user.sendPacketSilently(addMemberPacket)
        }

        fun despawn() {
            expireTask?.cancel()
            val protocolManager = PacketEvents.getAPI().protocolManager
            val user = protocolManager.getUser(player) ?: return

            if (spawnedEntityIds.isNotEmpty()) {
                val destroyPacket = WrapperPlayServerDestroyEntities(*spawnedEntityIds.toIntArray())
                user.sendPacketSilently(destroyPacket)
                spawnedEntityIds.clear()
            }

            for (teamName in teamNames) {
                val removeTeamPacket = WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    Optional.empty<WrapperPlayServerTeams.ScoreBoardTeamInfo>(),
                    emptyList()
                )
                user.sendPacketSilently(removeTeamPacket)
            }
            teamNames.clear()
        }

        private fun parseColor(colorName: String): NamedTextColor {
            return when (colorName.uppercase()) {
                "RED" -> NamedTextColor.RED
                "GREEN" -> NamedTextColor.GREEN
                "YELLOW" -> NamedTextColor.YELLOW
                "GOLD" -> NamedTextColor.GOLD
                "DARK_PURPLE" -> NamedTextColor.DARK_PURPLE
                "AQUA" -> NamedTextColor.AQUA
                else -> NamedTextColor.WHITE
            }
        }

        private fun formatTimeAgo(timestampSeconds: Long): String {
            val diffSec = (System.currentTimeMillis() / 1000) - timestampSeconds
            if (diffSec < 60) return "${diffSec}сек назад"
            val diffMin = diffSec / 60
            if (diffMin < 60) return "${diffMin}мин назад"
            val diffHours = diffMin / 60
            if (diffHours < 24) return "${diffHours}ч назад"
            val diffDays = diffHours / 24
            return "${diffDays}д назад"
        }
    }

    // ==========================================
    // 3. SESSION MANAGER & EVENT LISTENERS
    // ==========================================

    object SessionManager {
        private val activeSessions = ConcurrentHashMap<UUID, PhantomSession>()

        fun startSession(player: Player, query: LookupQuery, configManager: ConfigManager) {
            stopSession(player)

            Bukkit.getScheduler().runTaskAsynchronously(Acore.instance, Runnable {
                val cpPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect") as? CoreProtect
                if (cpPlugin == null || !cpPlugin.isEnabled) {
                    Bukkit.getScheduler().runTask(Acore.instance, Runnable {
                        player.sendMessage("§cCoreProtect не найден или отключен на сервере!")
                    })
                    return@Runnable
                }

                val api = cpPlugin.api
                val rawResults = api.performLookup(
                    query.timeSeconds,
                    query.users,
                    query.includeBlocks,
                    query.actions,
                    query.excludeBlocks,
                    null,
                    query.radius,
                    player.location
                ) ?: emptyList()

                val parsedResults = rawResults.mapNotNull { api.parseResult(it) }

                Bukkit.getScheduler().runTask(Acore.instance, Runnable {
                    if (parsedResults.isEmpty()) {
                        player.sendMessage("§cФантомная визуализация: ни одного объекта не найдено по вашему запросу.")
                        return@Runnable
                    }

                    val session = PhantomSession(player, query.durationSeconds)
                    session.spawnPhantomObjects(parsedResults, configManager)
                    activeSessions[player.uniqueId] = session
                    player.sendMessage("§aОтображено ${parsedResults.size.coerceAtMost(configManager.getInt("visualize.max-blocks-limit", 500))} фантомных объектов на ${query.durationSeconds} сек.")
                })
            })
        }

        fun stopSession(player: Player) {
            activeSessions.remove(player.uniqueId)?.despawn()
        }

        fun clearAllSessions() {
            activeSessions.values.forEach { it.despawn() }
            activeSessions.clear()
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        SessionManager.stopSession(event.player)
    }

    private object EntityIdGenerator {
        private val idCounter = AtomicInteger(2000000000)
        fun nextId(): Int = idCounter.getAndIncrement()
    }
}
