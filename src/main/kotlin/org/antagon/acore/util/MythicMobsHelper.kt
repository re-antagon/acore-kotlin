package org.antagon.acore.util

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

object MythicMobsHelper {
    private val logger = Logger.getLogger(MythicMobsHelper::class.java.name)

    /**
     * Retrieves an ItemStack from MythicMobs by its internal name.
     * Returns null if MythicMobs is not installed or the item doesn't exist.
     */
    fun getMythicItem(mythicItemName: String): ItemStack? {
        val mythicMobsPlugin = Bukkit.getPluginManager().getPlugin("MythicMobs")
        if (mythicMobsPlugin == null) {
            logger.warning("MythicMobs plugin not found, cannot get mythic item: $mythicItemName")
            return null
        }

        try {
            val mythicBukkitClass = try {
                Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, mythicMobsPlugin.javaClass.classLoader)
            } catch (e: ClassNotFoundException) {
                Class.forName("io.lumine.mythic.bukkit.MythicBukkit")
            }

            val instMethod = mythicBukkitClass.getMethod("inst")
            val mythicBukkit = instMethod.invoke(null) ?: return null

            val getItemManagerMethod = mythicBukkitClass.getMethod("getItemManager")
            val itemManager = getItemManagerMethod.invoke(mythicBukkit) ?: return null

            val getItemStackMethod = itemManager.javaClass.getMethod("getItemStack", String::class.java)
            return getItemStackMethod.invoke(itemManager, mythicItemName) as ItemStack?
        } catch (e: Exception) {
            logger.severe("Error retrieving MythicMobs item '$mythicItemName': ${e.message}")
            return null
        }
    }
}
