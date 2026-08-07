package org.antagon.acore.listener

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.antagon.acore.Acore
import org.antagon.acore.module.AcoreModule
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors

class SessionDataListener(
    private val plugin: Plugin = Acore.instance
) : AcoreModule, Listener {

    override val name: String = "Session Cache"

    override fun shouldEnable(): Boolean {
        return true
    }

    override fun enable() {
        registerEvents(plugin)
    }

    override fun disable() {
        super.disable()
        executor.submit {
            try {
                writer?.flush()
                writer?.close()
                writer = null
            } catch (_: Exception) {
            }
        }
        executor.shutdown()
    }

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "acore-session-writer")
    }
    private val cacheFile: File by lazy {
        val folder = plugin.dataFolder
        folder.mkdirs()
        File(folder, "session_cache")
    }

    private var writer: BufferedWriter? = null

    private fun getOrCreateWriter(): BufferedWriter {
        var w = writer
        if (w == null) {
            w = BufferedWriter(OutputStreamWriter(FileOutputStream(cacheFile, true), StandardCharsets.UTF_8))
            writer = w
        }
        return w
    }

    private val trackedPrefixes = listOf(
        "/give",
        "/item",
        "/i",
        "/egive",
        "/eitem",
        "/ei",
        "/mi give",
        "/mythicmobs give",
        "/eb give",
        "/essentials:give",
        "/essentials:item",
        "/essentials:i",
        "/minecraft:give",
        "/minecraft:item",
        "/eq give",
        "/grant"
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStateUpdate(event: PlayerGameModeChangeEvent) {
        val player = event.player
        val oldGm = player.gameMode
        val newGm = event.newGameMode
        val loc = player.location

        val detail = "GM: $oldGm -> $newGm at [${loc.world?.name}, ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}]"
        recordEntry("STATE_CHANGE", player, detail)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommandExecute(event: PlayerCommandPreprocessEvent) {
        val message = event.message.trim()
        val lowerMessage = message.lowercase()

        val matched = trackedPrefixes.any { prefix ->
            lowerMessage == prefix || lowerMessage.startsWith("$prefix ")
        }

        if (matched) {
            val player = event.player
            val loc = player.location
            val detail = "CMD: '$message' at [${loc.world?.name}, ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}]"
            recordEntry("CMD_EXEC", player, detail)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryUpdate(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode != GameMode.CREATIVE) return

        val item: ItemStack = when {
            isValidStack(event.cursor) -> event.cursor
            isValidStack(event.currentItem) -> event.currentItem!!
            else -> return
        }

        val itemData = parseStack(item)
        val loc = player.location
        val slot = event.slot

        val detail = "ITEM: $itemData (Slot: $slot) at [${loc.world?.name}, ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}]"
        recordEntry("INV_UPDATE", player, detail)
    }

    private fun isValidStack(stack: ItemStack?): Boolean {
        return stack != null && stack.type != Material.AIR && stack.amount > 0
    }

    private fun parseStack(stack: ItemStack): String {
        val builder = StringBuilder("${stack.type} x${stack.amount}")

        val customNameComp = stack.getData(DataComponentTypes.CUSTOM_NAME)
            ?: stack.getData(DataComponentTypes.ITEM_NAME)
        if (customNameComp != null) {
            val nameText = PlainTextComponentSerializer.plainText().serialize(customNameComp)
            if (nameText.isNotBlank()) {
                builder.append(" (Name: '$nameText')")
            }
        }

        val cmdData = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)
        if (cmdData != null && cmdData.floats().isNotEmpty()) {
            builder.append(" (CMD: ${cmdData.floats().joinToString(",")})")
        }

        return builder.toString()
    }

    private fun recordEntry(tag: String, player: Player, data: String) {
        val timestamp = LocalDateTime.now().format(timeFormatter)
        val line = "[$timestamp] [$tag] ${player.name} (${player.uniqueId}) -> $data"

        val encoded = Base64.getEncoder().encodeToString(line.toByteArray(StandardCharsets.UTF_8))

        // Guarantees strictly sequential FIFO writing on a single dedicated background thread
        executor.submit {
            try {
                val w = getOrCreateWriter()
                w.write(encoded)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
                try {
                    writer?.close()
                } catch (_: Exception) {
                }
                writer = null
            }
        }
    }
}
