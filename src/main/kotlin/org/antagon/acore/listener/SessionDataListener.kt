package org.antagon.acore.listener

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.antagon.acore.Acore
import org.antagon.acore.module.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
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
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs, without requiring any config option, whenever a player enters CREATIVE or
 * SURVIVAL game mode as well as every item that a player copies/takes out of the
 * creative inventory (including the full contents of shulker boxes and bundles).
 *
 * All disk I/O happens on a single dedicated writer thread that consumes an
 * explicit BlockingQueue<String>. Event handlers (called on the main server
 * thread) never touch the file or the BufferedWriter directly - they only
 * enqueue a fully-formatted text block. This removes any possibility of two
 * threads writing to the file at once and makes shutdown deterministic
 * (the queue is fully drained before the file is closed).
 */
class SessionDataListener(
    private val plugin: Plugin = Acore.instance
) : AcoreModule, Listener {

    override val name: String = "Session Cache"

    override fun shouldEnable(): Boolean = true

    override fun enable() {
        registerEvents(plugin)
        writerThread.start()
    }

    override fun disable() {
        super.disable()
        // stop accepting the "keep looping" condition and wake up a thread
        // that might be blocked on queue.poll(...)
        running.set(false)
        queue.offer(POISON_PILL)
        writerThread.join(SHUTDOWN_TIMEOUT_MS)
        closeWriterQuietly()
    }

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Producer/consumer: main thread(s) only ever call queue.offer(...).
    // "writer" and "cacheFile" are touched exclusively by writerThread,
    // so there is no shared mutable state crossing threads -> no race
    private val queue: BlockingQueue<String> = LinkedBlockingQueue()
    private val running = AtomicBoolean(true)
    private var writer: BufferedWriter? = null

    private val writerThread = Thread({ runWriterLoop() }, "acore-session-cache-writer").apply {
        isDaemon = true
    }

    private val cacheFile: File by lazy {
        val folder = plugin.dataFolder
        if (!folder.exists()) folder.mkdirs()
        File(folder, "session-cache")
    }

    private fun runWriterLoop() {
        // keep draining until told to stop AND the queue is empty,
        // so nothing enqueued right before shutdown gets lost
        while (running.get() || queue.isNotEmpty()) {
            val block = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            if (block === POISON_PILL) continue

            try {
                val out = ensureWriter()
                out.write(block)
                out.flush()
            } catch (_: Exception) {
                // corrupt/broken stream: drop it and reopen lazily on next write
                closeWriterQuietly()
            }
        }
        closeWriterQuietly()
    }

    private fun ensureWriter(): BufferedWriter {
        return writer ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(cacheFile, true), StandardCharsets.UTF_8)
        ).also { writer = it }
    }

    private fun closeWriterQuietly() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        } finally {
            writer = null
        }
    }

    // session tracking: entering CREATIVE or SURVIVAL
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        val newGameMode = event.newGameMode
        if (newGameMode != GameMode.CREATIVE && newGameMode != GameMode.SURVIVAL) return

        val player = event.player
        val tag = if (newGameMode == GameMode.CREATIVE) "CREATIVE" else "SURVIVAL"
        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Previous mode: ${event.player.gameMode}")
        lines.add("Location: ${formatLocation(player)}")
        writeBlock(tag, lines)
    }

    // give-style commands
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommandExecute(event: PlayerCommandPreprocessEvent) {
        val message = event.message.trim()
        val lowerMessage = message.lowercase()
        val matched = trackedCommandPrefixes.any { prefix ->
            lowerMessage == prefix || lowerMessage.startsWith("$prefix ")
        }
        if (!matched) return

        val player = event.player
        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Command: '$message'")
        lines.add("Location: ${formatLocation(player)}")
        writeBlock("CMD_EXEC", lines)
    }

    // items copied/taken out of the creative inventory
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreativeInventoryUpdate(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode != GameMode.CREATIVE) return

        val item: ItemStack = when {
            isValidStack(event.cursor) -> event.cursor!!
            isValidStack(event.currentItem) -> event.currentItem!!
            else -> return
        }

        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Slot: ${event.slot}")
        lines.addAll(describeItem(item, ""))
        lines.add("Location: ${formatLocation(player)}")
        writeBlock("ITEM_TAKEN", lines)
    }

    private val trackedCommandPrefixes = listOf(
        "/give", "/item", "/i", "/egive", "/eitem", "/ei", "/mi give",
        "/mythicmobs give", "/eb give", "/essentials:give", "/essentials:item",
        "/essentials:i", "/minecraft:give", "/minecraft:item", "/eq give", "/grant"
    )

    private fun isValidStack(stack: ItemStack?): Boolean {
        return stack != null && stack.type != Material.AIR && stack.amount > 0
    }

    private fun describeItem(stack: ItemStack, indent: String): List<String> {
        val lines = mutableListOf<String>()
        lines.add("${indent}Material: ${stack.type.key}")
        lines.add("${indent}Amount: ${stack.amount}")
        val displayName = getDisplayName(stack)
        lines.add("${indent}Name: ${displayName ?: "(none)"}")
        val tags = getItemTags(stack.type)
        lines.add("${indent}Tags: ${if (tags.isEmpty()) "(none)" else tags.joinToString(", ")}")
        lines.add("${indent}Components: ${getComponentsString(stack)}")
        val contents = getContainerContents(stack)
        if (contents.isNotEmpty()) {
            lines.add("${indent}Contents (${contents.size} item(s)):")
            contents.forEachIndexed { index, inner ->
                lines.add("$indent  [$index]")
                lines.addAll(describeItem(inner, "$indent    "))
            }
        }
        return lines
    }

    private fun getDisplayName(stack: ItemStack): String? {
        val customNameComp = stack.getData(DataComponentTypes.CUSTOM_NAME)
            ?: stack.getData(DataComponentTypes.ITEM_NAME)
        if (customNameComp != null) {
            val nameText = PlainTextComponentSerializer.plainText().serialize(customNameComp)
            if (nameText.isNotBlank()) return nameText
        }
        return null
    }

    private fun getItemTags(material: Material): List<String> {
        return try {
            Bukkit.getTags(Tag.REGISTRY_ITEMS, Material::class.java)
                .filter { it.isTagged(material) }
                .map { it.key.toString() }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getComponentsString(stack: ItemStack): String {
        return try {
            stack.itemMeta?.asComponentString?.takeIf { it.isNotBlank() } ?: "[]"
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun getContainerContents(stack: ItemStack): List<ItemStack> {
        val typeName = stack.type.name
        return try {
            when {
                typeName.endsWith("SHULKER_BOX") ->
                    stack.getData(DataComponentTypes.CONTAINER)?.contents()?.toList() ?: emptyList()
                typeName.endsWith("BUNDLE") ->
                    stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents()?.toList() ?: emptyList()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun formatLocation(player: Player): String {
        val loc = player.location
        return "${loc.world?.name}, ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}"
    }

    // producer side: only formats text and enqueues it - never touches the file
    private fun writeBlock(tag: String, detailLines: List<String>) {
        val timestamp = LocalDateTime.now().format(timeFormatter)
        val block = buildString {
            append("[$timestamp] [$tag]")
            append('\n')
            for (line in detailLines) {
                append("  ")
                append(line)
                append('\n')
            }
            append('\n')
        }
        if (!queue.offer(block)) {
            plugin.logger.warning("[SessionDataListener] session-cache queue rejected an entry")
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_MS = 5000L
        private val POISON_PILL = "\u0000__POISON_PILL__\u0000"
    }
}