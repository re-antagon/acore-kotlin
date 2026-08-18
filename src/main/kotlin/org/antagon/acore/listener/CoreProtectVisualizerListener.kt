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
import org.antagon.acore.core.AcoreModule
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
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketEventsItemStack
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
        val durationSeconds: Int,
        val debug: Boolean = false
    )

    object Parser {
        fun parseArgs(rawArgs: String, defaultConfig: ConfigManager): LookupQuery {
            val tokens = rawArgs.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

            val actions = mutableListOf<Int>()
            val includeBlocks = mutableListOf<String>()
            val excludeBlocks = mutableListOf<String>()
            val users = mutableListOf<String>()
            var timeSeconds = 3600 // Default 1 hour
            var radius = defaultConfig.getInt("visualize.default-radius", 0) // Default 0 = global (matching /co lookup)
            var durationSeconds = defaultConfig.getInt("visualize.default-duration", 30)
            var debug = false

            for (token in tokens) {
                if (token.equals("debug", ignoreCase = true)) {
                    debug = true
                    continue
                }

                val parts = token.split(":", limit = 2)
                if (parts.size < 2) continue
                val key = parts[0].trim().lowercase()
                val valStr = parts[1].trim()

                when (key) {
                    "a", "action" -> {
                        val subVals = valStr.lowercase().split(",")
                        for (rawSv in subVals) {
                            val sv = rawSv.trim()
                            when {
                                sv == "-block" || sv == "break" || sv == "-b" || sv == "0" -> actions.add(0)
                                sv == "+block" || sv == "place" || sv == "+b" || sv == "1" -> actions.add(1)
                                sv == "block" || sv == "b" -> { actions.add(0); actions.add(1) }
                                sv.contains("click") || sv.contains("interact") || sv == "2" -> actions.add(2)
                                sv.contains("container") || sv.contains("chest") || sv == "3" -> actions.add(3)
                                sv.contains("kill") || sv.contains("death") || sv.contains("mob") || sv.contains("entity") || sv == "4" -> actions.add(4)
                                sv.contains("item") || sv.contains("drop") || sv.contains("pickup") || sv == "5" -> actions.add(5)
                            }
                        }
                    }
                    "i", "include", "b", "block" -> {
                        includeBlocks.addAll(valStr.split(",").map { it.trim() })
                    }
                    "e", "exclude" -> {
                        excludeBlocks.addAll(valStr.split(",").map { it.trim() })
                    }
                    "u", "user" -> {
                        users.addAll(valStr.split(",").map { it.trim() })
                    }
                    "t", "time" -> {
                        timeSeconds = parseTimeString(valStr, 3600)
                    }
                    "r", "radius" -> {
                        radius = when (valStr.lowercase()) {
                            "#global", "global", "#world", "world", "all", "g" -> 0
                            else -> valStr.toIntOrNull() ?: 0
                        }
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
                durationSeconds = durationSeconds,
                debug = debug
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

        fun spawnPhantomObjects(results: List<CoreProtectAPI.ParseResult>, configManager: ConfigManager, query: LookupQuery? = null) {
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

            val maxLimit = configManager.getInt("visualize.max-blocks-limit", 500)
            val showHolograms = configManager.getBoolean("visualize.show-holograms", true)
            val displayMode = configManager.getString("visualize.display-mode", "exact_object")

            // Register Glow Teams
            setupGlowTeams(user)

            val locationStackMap = mutableMapOf<String, Int>()
            val displayResults = results.take(maxLimit)
            val queriedActions = query?.actions ?: emptyList()

            for (result in displayResults) {
                val loc = Location(player.world, result.x.toDouble(), result.y.toDouble(), result.z.toDouble())
                val locKey = "${result.x}:${result.y}:${result.z}"
                val stackIndex = locationStackMap.getOrDefault(locKey, 0)
                locationStackMap[locKey] = stackIndex + 1

                val actionId = result.actionId
                val rawMaterial = result.type?.takeIf { !it.isAir } ?: when (actionId) {
                    2 -> Material.LEVER
                    3 -> Material.CHEST
                    4 -> Material.BONE
                    5 -> Material.FEATHER
                    else -> Material.STONE
                }

                // Determine contextual event category based on query selector and internal table action ID
                val isItemQuery = queriedActions.contains(5)
                val isContainerQuery = queriedActions.contains(3)
                val isKillQuery = queriedActions.contains(4)
                val isClickQuery = queriedActions.contains(2)

                val (actionName, teamColorName, isItemOrEntity) = when {
                    isKillQuery -> {
                        val name = if (actionId == 3) "Убийство" else "Урон сущности"
                        Triple(name, configManager.getString("visualize.colors.kill", "DARK_PURPLE"), true)
                    }
                    isItemQuery -> {
                        val name = when (actionId) {
                            0 -> "Сброшено"
                            1 -> "Подобрано"
                            2 -> "Уничтожено"
                            3 -> "Рамка / Стойка"
                            else -> "Предмет"
                        }
                        Triple(name, configManager.getString("visualize.colors.item", "AQUA"), true)
                    }
                    isContainerQuery -> {
                        val name = when (actionId) {
                            0 -> "Взято из контейнера"
                            1 -> "Положено в контейнер"
                            else -> "Контейнер"
                        }
                        Triple(name, configManager.getString("visualize.colors.container", "GOLD"), true)
                    }
                    isClickQuery -> {
                        Triple("Клик", configManager.getString("visualize.colors.click", "YELLOW"), false)
                    }
                    else -> {
                        when (actionId) {
                            0 -> Triple("Сломано", configManager.getString("visualize.colors.removed", "RED"), false)
                            1 -> Triple("Поставлено", configManager.getString("visualize.colors.placed", "GREEN"), false)
                            2 -> Triple("Клик", configManager.getString("visualize.colors.click", "YELLOW"), false)
                            3 -> Triple("Контейнер", configManager.getString("visualize.colors.container", "GOLD"), true)
                            4 -> Triple("Убийство", configManager.getString("visualize.colors.kill", "DARK_PURPLE"), true)
                            else -> Triple("Предмет", configManager.getString("visualize.colors.item", "AQUA"), true)
                        }
                    }
                }
                val teamName = "acore_viz_${teamColorName.lowercase()}"

                val entityId = EntityIdGenerator.nextId()
                val uuid = UUID.randomUUID()
                spawnedEntityIds.add(entityId)

                // 1. Spawn BlockDisplay / ItemDisplay
                val materialToRender = if (displayMode == "exact_object") {
                    rawMaterial
                } else {
                    when {
                        isItemQuery -> Material.CYAN_CONCRETE
                        isContainerQuery -> Material.ORANGE_CONCRETE
                        isKillQuery -> Material.PURPLE_CONCRETE
                        isClickQuery -> Material.YELLOW_CONCRETE
                        actionId == 0 -> Material.RED_CONCRETE
                        actionId == 1 -> Material.GREEN_CONCRETE
                        else -> Material.YELLOW_STAINED_GLASS
                    }
                }

                val customBlockDataStr = if (displayMode == "exact_object" && rawMaterial.isBlock) {
                    try { result.blockData?.asString } catch (_: Throwable) { null }
                } else null

                val forceItem = isItemOrEntity || (!materialToRender.isBlock && materialToRender.isItem)
                if (forceItem) {
                    spawnItemDisplay(user, entityId, uuid, loc, materialToRender, teamName)
                } else {
                    spawnBlockDisplay(user, entityId, uuid, loc, materialToRender, teamName, customBlockDataStr)
                }

                // 2. Optional Hologram Label
                if (showHolograms) {
                    val holoEntityId = EntityIdGenerator.nextId()
                    val holoUuid = UUID.randomUUID()
                    spawnedEntityIds.add(holoEntityId)
                    val timeAgoStr = formatTimeAgo(result.timestamp)
                    val label = "§e[${materialToRender.name}] §f$actionName: §a${result.player} §7($timeAgoStr)"
                    val yOffset = 1.1 + (stackIndex * 0.35)
                    spawnTextHologram(user, holoEntityId, holoUuid, loc.clone().add(0.5, yOffset, 0.5), label)
                }
            }

            // Schedule auto-despawn
            expireTask = Bukkit.getScheduler().runTaskLater(Acore.instance, Runnable {
                despawn()
            }, durationSeconds * 20L)
        }

        private fun spawnBlockDisplay(user: User, entityId: Int, uuid: UUID, loc: Location, mat: Material, teamName: String, blockDataStr: String? = null) {
            val spawnPacket = WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.BLOCK_DISPLAY,
                Vector3d(loc.x, loc.y, loc.z),
                0f, 0f, 0f, 0,
                Optional.of(Vector3d.zero())
            )
            user.sendPacketSilently(spawnPacket)

            // Entity Metadata: Glowing = 0x40, BlockState = index 23
            val metadataList = mutableListOf<EntityData<*>>()
            metadataList.add(EntityData(0, EntityDataTypes.BYTE, (0x40).toByte()))
            try {
                val stateStr = blockDataStr ?: try { mat.createBlockData().asString } catch (_: Throwable) { "minecraft:" + mat.name.lowercase() }
                val wrappedState = WrappedBlockState.getByString(stateStr)
                metadataList.add(EntityData(23, EntityDataTypes.BLOCK_STATE, wrappedState.globalId))
            } catch (e: Throwable) {
                val defaultState = WrappedBlockState.getByString("minecraft:stone")
                metadataList.add(EntityData(23, EntityDataTypes.BLOCK_STATE, defaultState.globalId))
            }
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
                0f, 0f, 0f, 0,
                Optional.of(Vector3d.zero())
            )
            user.sendPacketSilently(spawnPacket)

            val metadataList = mutableListOf<EntityData<*>>()
            metadataList.add(EntityData(0, EntityDataTypes.BYTE, (0x40).toByte()))
            try {
                val itemName = "minecraft:" + mat.name.lowercase()
                val itemType = ItemTypes.getByName(itemName) ?: ItemTypes.STONE
                val pStack = PacketEventsItemStack.builder().type(itemType).amount(1).build()
                metadataList.add(EntityData(23, EntityDataTypes.ITEMSTACK, pStack))
            } catch (e: Throwable) {
                // fallback
            }
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
                0f, 0f, 0f, 0,
                Optional.of(Vector3d.zero())
            )
            user.sendPacketSilently(spawnPacket)

            val metadataList = mutableListOf<EntityData<*>>()
            // Index 15: Billboard constraints (Byte) in 1.20.5+ / 1.21+ protocol. 3 = CENTER (faces camera 360°)
            metadataList.add(EntityData(15, EntityDataTypes.BYTE, 3.toByte()))
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
                        teamInfo,
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
                null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
                listOf(entityUuidStr)
            )
            user.sendPacketSilently(addMemberPacket)
        }

        fun despawn() {
            expireTask?.cancel()
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

            if (spawnedEntityIds.isNotEmpty()) {
                val destroyPacket = WrapperPlayServerDestroyEntities(*spawnedEntityIds.toIntArray())
                user.sendPacketSilently(destroyPacket)
                spawnedEntityIds.clear()
            }

            for (teamName in teamNames) {
                val removeTeamPacket = WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
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

        private fun formatTimeAgo(timestamp: Long): String {
            val sec = if (timestamp > 100_000_000_000L) timestamp / 1000 else timestamp
            val nowSec = System.currentTimeMillis() / 1000
            val diffSec = (nowSec - sec).coerceAtLeast(0)
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
                val searchLocation = if (query.radius > 0) player.location else null
                val rawResults = api.performLookup(
                    query.timeSeconds,
                    query.users,
                    null,
                    query.includeBlocks?.map { it as Any },
                    query.excludeBlocks?.map { it as Any },
                    query.actions,
                    query.radius,
                    searchLocation
                ) ?: emptyList()

                val allParsed = rawResults.mapNotNull { api.parseResult(it) }
                val queriedActions = query.actions
                val parsedResults = if (queriedActions != null && queriedActions.isNotEmpty()) {
                    allParsed.filter { queriedActions.contains(it.actionId) }
                } else {
                    allParsed
                }

                Bukkit.getScheduler().runTask(Acore.instance, Runnable {
                    if (query.debug) {
                        Acore.instance.logger.info("[Acore Debug] Query actions=${query.actions}, users=${query.users}, radius=${query.radius}. Received ${rawResults.size} raw records, ${parsedResults.size} parsed & filtered.")
                        parsedResults.take(10).forEachIndexed { i, res ->
                            Acore.instance.logger.info("[Acore Debug #${i + 1}] actionId=${res.actionId}, type=${res.type}, ts=${res.timestamp}, pos=(${res.x}, ${res.y}, ${res.z}), player=${res.player}")
                        }
                    }

                    if (parsedResults.isEmpty()) {
                        player.sendMessage("§cФантомная визуализация: ни одного объекта не найдено по вашему запросу.")
                        return@Runnable
                    }

                    val session = PhantomSession(player, query.durationSeconds)
                    session.spawnPhantomObjects(parsedResults, configManager, query)
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
