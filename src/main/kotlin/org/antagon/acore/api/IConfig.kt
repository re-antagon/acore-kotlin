package org.antagon.acore.api

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

interface IConfig {
    fun load()
    fun save()
    fun reload()
    fun updateConfigVersion(): FileConfiguration

    fun getString(path: String): String?
    fun getString(path: String, defaultValue: String): String
    fun getInt(path: String): Int
    fun getInt(path: String, defaultValue: Int): Int
    fun getBoolean(path: String): Boolean
    fun getBoolean(path: String, defaultValue: Boolean): Boolean
    fun getDouble(path: String): Double
    fun getDouble(path: String, defaultValue: Double): Double

    fun getStringList(path: String): List<String>

    fun set(path: String, value: Any)

    fun contains(path: String): Boolean

    fun getSection(path: String): ConfigurationSection?

    fun getKeys(deep: Boolean): Set<String>
}
