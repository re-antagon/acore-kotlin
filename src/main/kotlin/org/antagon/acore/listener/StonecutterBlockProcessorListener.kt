package org.antagon.acore.listener

import org.antagon.acore.api.IConfig
import org.antagon.acore.util.MythicMobsHelper
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.ArrayList
import java.util.HashSet

class StonecutterBlockProcessorListener(
    private val plugin: JavaPlugin,
    private val config: IConfig
) : Listener {

    private val trackedItems: MutableSet<Item> = HashSet()
    private val crafts = ArrayList<BlockProcessorCraft>()

    init {
        loadCrafts()
        // Start scheduled task to check all dropped items every 5 ticks
        startProcessingTask()
    }

    private fun loadCrafts() {
        crafts.clear()
        val section = config.getSection("stonecutterBlockProcessor.crafts") ?: return
        for (key in section.getKeys(false)) {
            val craftSection = section.getConfigurationSection(key) ?: continue
            val materialStr = craftSection.getString("MATERIAL") ?: continue
            val resultStr = craftSection.getString("RESULT") ?: continue

            // Parse MATERIAL
            val materialParts = materialStr.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (materialParts.isEmpty()) continue
            val materialId = materialParts[0]
            val inputAmount = if (materialParts.size > 1) materialParts[1].toIntOrNull() ?: 1 else 1

            var inputMaterial: Material? = null
            var inputTag: NamespacedKey? = null

            if (materialId.startsWith("#")) {
                val tagStr = materialId.substring(1)
                inputTag = if (tagStr.contains(":")) {
                    val parts = tagStr.split(":", limit = 2)
                    NamespacedKey(parts[0], parts[1])
                } else {
                    NamespacedKey.minecraft(tagStr)
                }
            } else {
                val matName = if (materialId.contains(":")) {
                    materialId.split(":", limit = 2)[1]
                } else {
                    materialId
                }
                inputMaterial = Material.matchMaterial(matName)
                if (inputMaterial == null) {
                    plugin.logger.warning("Invalid input material '$materialId' in stonecutterBlockProcessor craft '$key'")
                    continue
                }
            }

            // Parse RESULT
            val resultParts = resultStr.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (resultParts.isEmpty()) continue
            val resultId = resultParts[0]
            val resultAmount = if (resultParts.size > 1) resultParts[1].toIntOrNull() ?: 1 else 1

            val resultType: ResultType
            val resultName: String

            if (resultId.startsWith("mythic:", ignoreCase = true)) {
                resultType = ResultType.MYTHIC
                resultName = resultId.substring(7)
            } else {
                resultType = ResultType.VANILLA
                resultName = if (resultId.contains(":")) {
                    resultId.split(":", limit = 2)[1]
                } else {
                    resultId
                }.uppercase()

                if (Material.matchMaterial(resultName) == null) {
                    plugin.logger.warning("Invalid result material '$resultId' in stonecutterBlockProcessor craft '$key'")
                    continue
                }
            }

            crafts.add(
                BlockProcessorCraft(
                    name = key,
                    inputMaterial = inputMaterial,
                    inputTag = inputTag,
                    inputAmount = inputAmount,
                    resultType = resultType,
                    resultName = resultName,
                    resultAmount = resultAmount
                )
            )
        }
        plugin.logger.info("Loaded ${crafts.size} stonecutterBlockProcessor crafts")
    }

    private fun startProcessingTask() {
        object : BukkitRunnable() {
            override fun run() {
                processingTask()
            }
        }.runTaskTimer(plugin, 0L, 5L) // Run every 5 ticks (0.25 seconds)
    }

    /**
     * Adds items to the tracking set if they match any craft input.
     */
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val item = event.itemDrop
        val type = item.itemStack.type

        if (crafts.any { it.matchesMaterial(type) }) {
            trackedItems.add(item)
        }
    }

    /**
     * Starts the single synchronous task that runs every 5 ticks.
     */
    private fun processingTask() {
        if (trackedItems.isEmpty()) {
            return
        }

        val iterator = trackedItems.iterator()

        while (iterator.hasNext()) {
            val item = iterator.next()

            // 1. Clean up invalid items (picked up, despawned, burned, etc.)
            if (!item.isValid) {
                iterator.remove()
                continue
            }

            // 2. Logic Check: Is it on a Stonecutter?
            val location = item.location
            val blockBelow = location.block.getRelative(BlockFace.DOWN)
            val block = location.block

            if (blockBelow.type == Material.STONECUTTER || block.type == Material.STONECUTTER) {
                val stack = item.itemStack
                // Find first craft that matches the material and amount
                val matchingCraft = crafts.firstOrNull { craft ->
                    craft.matchesMaterial(stack.type) && stack.amount >= craft.inputAmount
                }

                if (matchingCraft != null) {
                    convertItem(item, matchingCraft)
                    // Remove from tracking set as it is now processed/deleted
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Handles the specific game logic for converting the item using the matching craft.
     */
    private fun convertItem(item: Item, craft: BlockProcessorCraft) {
        val droppedBlockAmount = item.itemStack.amount
        val craftApplications = droppedBlockAmount / craft.inputAmount
        val totalResultToDrop = craftApplications * craft.resultAmount
        val remainingAmount = droppedBlockAmount % craft.inputAmount

        if (totalResultToDrop > 0) {
            when (craft.resultType) {
                ResultType.VANILLA -> {
                    val material = Material.matchMaterial(craft.resultName)
                    if (material != null) {
                        val resultStack = ItemStack(material, totalResultToDrop)
                        item.world.dropItem(item.location, resultStack)
                    } else {
                        plugin.logger.warning("Vanilla material '${craft.resultName}' not found, skipping craft application.")
                    }
                }
                ResultType.MYTHIC -> {
                    val resultStack = MythicMobsHelper.getMythicItem(craft.resultName)
                    if (resultStack != null) {
                        resultStack.amount = totalResultToDrop
                        item.world.dropItem(item.location, resultStack)
                    } else {
                        plugin.logger.warning("MythicMobs item '${craft.resultName}' not found or plugin disabled, skipping craft application.")
                    }
                }
            }
        }

        if (remainingAmount > 0) {
            val newStack = item.itemStack.clone()
            newStack.amount = remainingAmount
            item.itemStack = newStack
        } else {
            item.remove()
        }
    }

    data class BlockProcessorCraft(
        val name: String,
        val inputMaterial: Material?,
        val inputTag: NamespacedKey?,
        val inputAmount: Int,
        val resultType: ResultType,
        val resultName: String,
        val resultAmount: Int
    ) {
        fun matchesMaterial(material: Material): Boolean {
            if (inputMaterial != null) {
                return material == inputMaterial
            }
            if (inputTag != null) {
                return isMaterialInTag(material, inputTag)
            }
            return false
        }

        private fun isMaterialInTag(material: Material, tagKey: NamespacedKey): Boolean {
            val itemTag = Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material::class.java)
            if (itemTag != null && itemTag.isTagged(material)) {
                return true
            }
            val blockTag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, tagKey, Material::class.java)
            if (blockTag != null && blockTag.isTagged(material)) {
                return true
            }
            return false
        }
    }

    enum class ResultType {
        VANILLA,
        MYTHIC
    }
}