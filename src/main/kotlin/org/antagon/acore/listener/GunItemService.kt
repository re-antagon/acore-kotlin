package org.antagon.acore.listener

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.antagon.acore.core.ConfigManager
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

// the item is identified exclusively via a PersistentDataContainer marker
// physicsgun:gun_marker, so a vanilla item of the same material can
// never trigger the module functionality
class GunItemService(
    private val plugin: Plugin,
    private val configManager: ConfigManager
) {

    companion object {
        // marker key as fixed by the spec: physicsgun:gun_marker (BYTE)
        val MARKER_KEY: NamespacedKey = requireNotNull(NamespacedKey.fromString("physicsgun:gun_marker"))
    }

    private val logger = Logger.getLogger(GunItemService::class.java.name)

    fun isGun(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        val marker = meta.persistentDataContainer.get(MARKER_KEY, PersistentDataType.BYTE)
        return marker == 1.toByte()
    }

    fun createGun(): ItemStack {
        val materialName = configManager.getString("physicsGun.gun.material", "BLAZE_ROD")
        var material = Material.matchMaterial(materialName)
        if (material == null || !material.isItem || material.isAir) {
            logger.warning("Invalid physicsGun.gun.material '$materialName', falling back to BLAZE_ROD")
            material = Material.BLAZE_ROD
        }

        val item = ItemStack(material)
        val meta = item.itemMeta
        val legacy = LegacyComponentSerializer.legacyAmpersand()

        meta.persistentDataContainer.set(MARKER_KEY, PersistentDataType.BYTE, 1.toByte())
        meta.displayName(legacy.deserialize(configManager.getString("physicsGun.gun.display-name", "&bPhysics Gun")))

        val loreLines = configManager.getStringList("physicsGun.gun.lore")
        if (loreLines.isNotEmpty()) {
            meta.lore(loreLines.map { legacy.deserialize(it) })
        }

        val customModelData = configManager.getInt("physicsGun.gun.custom-model-data", 0)
        if (customModelData > 0) {
            @Suppress("DEPRECATION")
            meta.setCustomModelData(customModelData)
        }

        item.itemMeta = meta
        return item
    }
}
