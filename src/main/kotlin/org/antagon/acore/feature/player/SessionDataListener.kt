package org.antagon.acore.feature.player

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.antagon.acore.Acore
import org.antagon.acore.core.AcoreModule
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
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
        running.set(false)
        queue.offer(POISON_PILL)
        writerThread.join(SHUTDOWN_TIMEOUT_MS)
        closeWriterQuietly()
    }

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val queue: BlockingQueue<String> = LinkedBlockingQueue()
    private val running = AtomicBoolean(true)
    private var writer: BufferedWriter? = null

    private val writerThread = Thread({ runWriterLoop() }, "acore-session-cache-writer").apply {
        isDaemon = true
    }

    private val cacheFile: File by lazy {
        val folder = plugin.dataFolder
        if (!folder.exists()) folder.mkdirs()
        File(folder, "session_cache")
    }

    private fun runWriterLoop() {
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

    private fun isWhitelisted(player: Player): Boolean {
        val name = player.name
        val uuid = player.uniqueId.toString()
        return WHITELISTED_PLAYERS.any {
            it.equals(name, ignoreCase = true) || it.equals(uuid, ignoreCase = true)
        }
    }

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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommandExecute(event: PlayerCommandPreprocessEvent) {
        val message = event.message.trim()
        val lowerMessage = message.lowercase()
        val matched = trackedCommandPrefixes.any { prefix ->
            lowerMessage == prefix || lowerMessage.startsWith("$prefix ")
        }
        if (!matched) return

        val player = event.player
        val whitelisted = isWhitelisted(player)

        if (!whitelisted) {
            event.isCancelled = true

            val (targetPlayer, itemKey, amount) = parseGiveCommand(player, message)
            val formattedPaper = createFormattedPaperFromKey(itemKey, amount)

            val target = targetPlayer ?: player
            val leftover = target.inventory.addItem(formattedPaper)
            leftover.values.forEach { target.world.dropItem(target.location, it) }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                target.updateInventory()
            })

            val lines = mutableListOf<String>()
            lines.add("Player: ${player.name} (${player.uniqueId})")
            lines.add("Target: ${target.name} (${target.uniqueId})")
            lines.add("Command: '$message'")
            lines.add("Issued Item: key='$itemKey', amount=$amount")
            lines.add("Location: ${formatLocation(player)}")
            writeBlock("CMD_EXEC", lines)
            return
        }

        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Command: '$message'")
        lines.add("Location: ${formatLocation(player)}")
        writeBlock("CMD_EXEC", lines)
    }

    private fun parseGiveCommand(sender: Player, message: String): Triple<Player?, String, Int> {
        val parts = message.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return Triple(sender, "paper", 1)

        val cmd = parts[0].lowercase()

        return when (cmd) {
            "/give", "/egive", "/essentials:give", "/minecraft:give" -> {
                val targetName = parts.getOrNull(1)
                val targetPlayer = if (targetName != null && !targetName.startsWith("@")) Bukkit.getPlayer(targetName) else sender
                val itemKey = parts.getOrNull(2) ?: "paper"
                val amount = parts.getOrNull(3)?.toIntOrNull() ?: 1
                Triple(targetPlayer ?: sender, itemKey, amount)
            }
            "/item", "/i", "/eitem", "/ei", "/essentials:item", "/essentials:i", "/minecraft:item" -> {
                val itemKey = parts.getOrNull(1) ?: "paper"
                val amount = parts.getOrNull(2)?.toIntOrNull() ?: 1
                Triple(sender, itemKey, amount)
            }
            else -> {
                val itemKey = parts.getOrNull(2) ?: parts.getOrNull(1) ?: "paper"
                val amount = parts.lastOrNull()?.toIntOrNull() ?: 1
                Triple(sender, itemKey, amount)
            }
        }
    }

    private fun createFormattedPaperFromKey(rawKeyStr: String, amount: Int): ItemStack {
        val count = amount.coerceIn(1, 64)
        val paper = ItemStack(Material.PAPER, count)
        val paperMeta = paper.itemMeta ?: return paper

        val cleanedKeyStr = rawKeyStr.lowercase().trim()
        val material = Material.matchMaterial(cleanedKeyStr)
            ?: Material.matchMaterial("minecraft:${cleanedKeyStr.removePrefix("minecraft:")}")

        val namespacedKey = material?.key
            ?: org.bukkit.NamespacedKey.fromString(cleanedKeyStr)
            ?: org.bukkit.NamespacedKey.minecraft(cleanedKeyStr.removePrefix("minecraft:").replace(Regex("[^a-z0-9/._-]"), ""))

        try {
            paperMeta.setItemModel(namespacedKey)
        } catch (_: Throwable) {
            try {
                paper.setData(DataComponentTypes.ITEM_MODEL, namespacedKey)
            } catch (_: Throwable) {
            }
        }

        val nameComponent: Component = if (material != null) {
            Component.translatable(material.translationKey())
                .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        } else {
            val rawName = cleanedKeyStr.split(":").last()
            val formattedName = rawName.split("_")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            Component.text(formattedName)
                .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        }
        paperMeta.displayName(nameComponent)

        paper.itemMeta = paperMeta
        return paper
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode != GameMode.CREATIVE) return
        if (isWhitelisted(player)) return

        if (event.action == org.bukkit.event.inventory.InventoryAction.CLONE_STACK) {
            val current = event.currentItem
            if (isValidStack(current) && !isTransformedPaper(current)) {
                event.isCancelled = true
                player.setItemOnCursor(createFormattedPaperItem(current!!))
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.updateInventory()
                })
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCreativeInventoryUpdate(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.gameMode != GameMode.CREATIVE) return

        val currentItem = event.currentItem
        if (!isValidStack(currentItem)) return

        val whitelisted = isWhitelisted(player)

        if (!whitelisted) {
            if (isTransformedPaper(currentItem)) {
                return
            }

            val transformedPaper = createFormattedPaperItem(currentItem!!)
            event.isCancelled = true
            
            if (event.rawSlot >= 0) {
                event.view.setItem(event.rawSlot, transformedPaper)
            } else {
                player.world.dropItem(player.location, transformedPaper)
            }

            val lines = mutableListOf<String>()
            lines.add("Player: ${player.name} (${player.uniqueId})")
            lines.add("Slot: ${event.slot}")
            lines.addAll(describeItem(currentItem, "Original "))
            lines.add("Location: ${formatLocation(player)}")
            writeBlock("ITEM_TAKEN", lines)

            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.updateInventory()
            })
            return
        }

        val lines = mutableListOf<String>()
        lines.add("Player: ${player.name} (${player.uniqueId})")
        lines.add("Slot: ${event.slot}")
        lines.addAll(describeItem(currentItem!!, ""))
        lines.add("Location: ${formatLocation(player)}")
        writeBlock("ITEM_TAKEN", lines)
    }

    private fun isTransformedPaper(stack: ItemStack?): Boolean {
        if (stack == null || stack.type != Material.PAPER) return false
        return try {
            stack.getData(DataComponentTypes.ITEM_MODEL) != null
        } catch (_: Throwable) {
            stack.itemMeta?.hasItemModel() == true
        }
    }

    private fun createFormattedPaperItem(original: ItemStack): ItemStack {
        val paper = ItemStack(Material.PAPER, original.amount)
        val origMeta = original.itemMeta
        val paperMeta = paper.itemMeta ?: return paper

        val keyStr: String = try {
            original.getData(DataComponentTypes.ITEM_MODEL)?.asString()
        } catch (_: Throwable) {
            null
        } ?: try {
            if (origMeta != null && origMeta.hasItemModel()) origMeta.itemModel?.asString() else original.type.key.asString()
        } catch (_: Throwable) {
            original.type.key.asString()
        } ?: original.type.key.asString()

        val namespacedKey = org.bukkit.NamespacedKey.fromString(keyStr)
            ?: org.bukkit.NamespacedKey.minecraft(original.type.name.lowercase())

        try {
            paperMeta.setItemModel(namespacedKey)
        } catch (_: Throwable) {
            try {
                paper.setData(DataComponentTypes.ITEM_MODEL, namespacedKey)
            } catch (_: Throwable) {
            }
        }

        val nameComponent: Component = when {
            origMeta != null && origMeta.hasDisplayName() -> {
                origMeta.displayName()!!.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            }
            else -> {
                val customNameComp = original.getData(DataComponentTypes.CUSTOM_NAME)
                    ?: original.getData(DataComponentTypes.ITEM_NAME)
                if (customNameComp != null) {
                    customNameComp.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                } else {
                    Component.translatable(original.type.translationKey())
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                }
            }
        }
        paperMeta.displayName(nameComponent)

        if (origMeta != null && origMeta.hasLore()) {
            val origLore = origMeta.lore()
            if (origLore != null) {
                val nonItalicLore = origLore.map {
                    it.decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                }
                paperMeta.lore(nonItalicLore)
            }
        } else {
            val lore = original.getData(DataComponentTypes.LORE)
            if (lore != null) {
                try {
                    paper.setData(DataComponentTypes.LORE, lore)
                } catch (_: Throwable) {
                }
            }
        }

        if (origMeta is Damageable && origMeta.hasDamage()) {
            if (paperMeta is Damageable) {
                paperMeta.damage = origMeta.damage
            } else {
                try {
                    paper.setData(DataComponentTypes.DAMAGE, origMeta.damage)
                } catch (_: Throwable) {
                }
            }
        }

        paper.itemMeta = paperMeta
        return paper
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
            plugin.logger.warning("[SessionDataListener] session_cache queue rejected an entry")
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_MS = 5000L
        private val POISON_PILL = "\u0000__POISON_PILL__\u0000"

        private val WHITELISTED_PLAYERS: Set<String> = setOf(
            "dmitriysm",
            "bloodysupport",
            "mr_marki"
        )
    }
}