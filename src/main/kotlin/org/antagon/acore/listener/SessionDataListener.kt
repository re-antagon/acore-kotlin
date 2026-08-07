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
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Logs, without requiring any config option, whenever a player enters CREATIVE or
 * SURVIVAL game mode as well as every item that a player copies/takes out of the
 * creative inventory (including the full contents of shulker boxes and bundles).
 *
 * Every entry is written as its own human-readable, multi-line block to
 * `plugins/acore/session-cache` (no file extension). Each time a player enters
 * creative mode a brand new session block is appended - previous sessions are
 * never rewritten or merged, so the file can be read top-to-bottom as a
 * timeline of independent sessions.
 */
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
        executor.shutdown()
    }

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // guarantees strictly sequential FIFO writing on a single dedicated background thread,
    // so concurrent events never interleave or corrupt the file.
    private val executor = Executors.newSingleThreadExecutor()

    private val cacheFile: File by lazy {
        val folder = plugin.dataFolder
        if (!folder.exists()) {
            folder.mkdirs()
        }
        File(folder, "session-cache")
    }

    private val trackedCommandPrefixes = listOf(
        "/give",
        "/item",
        "/i",
        "/mi give",
        "/mythicmobs give",
        "/eb give",
        "/essentials:give",
        "/minecraft:give",
        "/eq give",
        "/grant"
    )

    // session tracking: entering CREATIVE or SURVIVAL
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        val newGameMode = event.newGameMode
        if (newGameMode != GameMode.CREATIVE && newGameMode != GameMode.SURVIVAL) {
            return
        }

        val player = event.player
        val tag = if (newGameMode == GameMode.CREATIVE) "CREATIVE" else "SURVIVAL"

        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Previous mode: ${event.player.gameMode}")
        lines.add("Location: ${formatLocation(player)}")

        // always create a brand new session block - never update/merge a previous one
        writeBlock(tag, lines)
    }

    // give-style commands (kept for completeness, multi-line formatted)
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

    private fun isValidStack(stack: ItemStack?): Boolean {
        return stack != null && stack.type != Material.AIR && stack.amount > 0
    }

    // item description helpers: amount, name, tags, all components and,
    // for shulker boxes/bundles, their full nested contents
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
            if (nameText.isNotBlank()) {
                return nameText
            }
        }
        return null
    }

    // returns every registered item tag (e.g. minecraft:swords, minecraft:enchantable)
    // that the given material belongs to
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

    // returns a full, human-readable dump of every data component present on the
    // item stack (enchantments, custom model data, attribute modifiers, etc.)
    private fun getComponentsString(stack: ItemStack): String {
        return try {
            stack.itemMeta?.asComponentString?.takeIf { it.isNotBlank() } ?: "[]"
        } catch (_: Exception) {
            "[]"
        }
    }

    // if the stack is a shulker box or a bundle, returns the items stored inside it
    private fun getContainerContents(stack: ItemStack): List<ItemStack> {
        val typeName = stack.type.name
        return try {
            when {
                typeName.endsWith("SHULKER_BOX") -> {
                    stack.getData(DataComponentTypes.CONTAINER)?.contents()?.toList() ?: emptyList()
                }
                typeName.endsWith("BUNDLE") -> {
                    stack.getData(DataComponentTypes.BUNDLE_CONTENTS)?.contents()?.toList() ?: emptyList()
                }
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

    // writing: readable, multi-line blocks - never rewritten, only appended
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
            // blank separator line so every entry/session stays visually independent
            append('\n')
        }

        val bytes = block.toByteArray(StandardCharsets.UTF_8)

        executor.submit {
            try {
                FileOutputStream(cacheFile, true).use { out ->
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }
}
