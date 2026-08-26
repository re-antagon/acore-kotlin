package org.antagon.acore.core

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.logging.Level
import java.util.logging.Logger

class ConfigManager private constructor(
    private val configFile: File,
    private val logger: Logger
) {
    private var config: FileConfiguration

    init {
        createConfigIfAbsent()
        this.config = YamlConfiguration.loadConfiguration(configFile)
        this.config = updateConfiguration(this.config)
    }

    companion object {
        private const val REQUIRED_VERSION = 4
        private var instance: ConfigManager? = null

        fun initialize(dataFolder: File, pluginLogger: Logger): ConfigManager {
            if (instance == null) {
                if (!dataFolder.exists() || !dataFolder.isDirectory) {
                    if (dataFolder.mkdirs()) {
                        pluginLogger.info("Created plugin data folder: ${dataFolder.absolutePath}")
                    }
                }
                val configFile = File(dataFolder, "config.yml")
                instance = ConfigManager(configFile, pluginLogger)
            }
            return instance!!
        }

        fun getInstance(): ConfigManager {
            return instance ?: throw IllegalStateException("ConfigManager has not been initialized.")
        }
    }

    private fun getDefaultConfig(): FileConfiguration {
        val resourceStream = javaClass.getResourceAsStream("/config.yml")
        if (resourceStream == null) {
            logger.severe("Default configuration resource '/config.yml' not found.")
            return YamlConfiguration()
        }
        return YamlConfiguration.loadConfiguration(InputStreamReader(resourceStream))
    }

    private fun createConfigIfAbsent() {
        if (!configFile.exists()) {
            try {
                if (configFile.createNewFile()) {
                    logger.info("Created a new configuration file.")
                    val defaultConfig = getDefaultConfig()
                    defaultConfig.save(configFile)
                }
            } catch (e: IOException) {
                logger.log(Level.SEVERE, "Failed to create config file: " + e.message, e)
            }
        }
    }

    private fun updateConfiguration(targetConfig: FileConfiguration): FileConfiguration {
        val currentVersion = targetConfig.getInt("config-version", 0)
        if (currentVersion < REQUIRED_VERSION) {
            logger.info("Updating configuration to version $REQUIRED_VERSION")
            targetConfig.set("config-version", REQUIRED_VERSION)
        }

        val defaultConfig = getDefaultConfig()
        defaultConfig.getKeys(true).stream()
            .filter { key -> !targetConfig.contains(key) }
            .forEach { key ->
                targetConfig.set(key, defaultConfig.get(key))
                logger.info("Added missing configuration key: $key = ${defaultConfig.get(key)}")
            }

        try {
            targetConfig.save(configFile)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save updated configuration: " + e.message, e)
        }

        return targetConfig
    }

    fun load() {
        try {
            config = YamlConfiguration.loadConfiguration(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to load configuration from " + configFile.name + ": " + e.message)
        }
    }

    fun save() {
        try {
            config.save(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to save configuration to " + configFile.path + ": " + e.message)
        }
    }

    fun reload() {
        try {
            this.config = YamlConfiguration.loadConfiguration(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to reload configuration: " + e.message)
        }
    }

    fun getString(path: String): String? {
        return config.getString(path)
    }

    fun getString(path: String, defaultValue: String): String {
        return config.getString(path, defaultValue) ?: defaultValue
    }

    fun getInt(path: String): Int {
        return config.getInt(path)
    }

    fun getInt(path: String, defaultValue: Int): Int {
        return config.getInt(path, defaultValue)
    }

    fun getBoolean(path: String): Boolean {
        return config.getBoolean(path)
    }

    fun getBoolean(path: String, defaultValue: Boolean): Boolean {
        return config.getBoolean(path, defaultValue)
    }

    fun getDouble(path: String): Double {
        return config.getDouble(path)
    }

    fun getDouble(path: String, defaultValue: Double): Double {
        return config.getDouble(path, defaultValue)
    }

    fun getStringList(path: String): List<String> {
        return config.getStringList(path)
    }

    fun set(path: String, value: Any) {
        config.set(path, value)
    }

    fun contains(path: String): Boolean {
        return config.contains(path)
    }

    fun getSection(path: String): ConfigurationSection? {
        return config.getConfigurationSection(path)
    }

    fun getKeys(deep: Boolean): Set<String> {
        return config.getKeys(deep)
    }
}