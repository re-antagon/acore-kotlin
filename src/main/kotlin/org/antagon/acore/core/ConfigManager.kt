package org.antagon.acore.core

import org.antagon.acore.api.IConfig
import org.antagon.acore.config.ConfigFileCreator
import org.antagon.acore.config.ConfigUpdater
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.util.logging.Logger

class ConfigManager private constructor(
    private val configFile: File,
    private val logger: Logger,
    private val configFileCreator: ConfigFileCreator,
    private val configUpdater: ConfigUpdater
) : IConfig {
    private lateinit var config: FileConfiguration

    init {
        val defaultConfig = configFileCreator.getDefaultConfig()
        this.config = configFileCreator.loadConfig(configFile)
        this.config = configUpdater.updateConfiguration(config, defaultConfig, configFile)
    }

    companion object {
        private var instance: ConfigManager? = null

        fun initialize(dataFolder: File, pluginLogger: Logger): ConfigManager {
            if (instance == null) {
                val configFileCreator = ConfigFileCreator(dataFolder, pluginLogger)
                val configUpdater = ConfigUpdater(pluginLogger)
                instance = ConfigManager(
                    configFileCreator.getConfigFile(),
                    pluginLogger,
                    configFileCreator,
                    configUpdater
                )
            }
            return instance!!
        }

        fun getInstance(): ConfigManager {
            return instance ?: throw IllegalStateException("ConfigManager has not been initialized.")
        }
    }

    override fun load() {
        try {
            config = configFileCreator.loadConfig(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to load configuration from " + configFile.name + ": " + e.message)
        }
    }

    override fun save() {
        try {
            config.save(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to save configuration to " + configFile.path + ": " + e.message)
        }
    }

    override fun reload() {
        try {
            this.config = configFileCreator.loadConfig(configFile)
        } catch (e: Exception) {
            logger.severe("Failed to reload configuration: " + e.message)
        }
    }

    override fun updateConfigVersion(): FileConfiguration {
        val defaultConfig = configFileCreator.getDefaultConfig()
        this.config = configUpdater.updateConfiguration(config, defaultConfig, configFile)
        return this.config
    }

    override fun getString(path: String): String? {
        return config.getString(path)
    }

    override fun getString(path: String, defaultValue: String): String {
        return config.getString(path, defaultValue)
    }

    override fun getInt(path: String): Int {
        return config.getInt(path)
    }

    override fun getInt(path: String, defaultValue: Int): Int {
        return config.getInt(path, defaultValue)
    }

    override fun getBoolean(path: String): Boolean {
        return config.getBoolean(path)
    }

    override fun getBoolean(path: String, defaultValue: Boolean): Boolean {
        return config.getBoolean(path, defaultValue)
    }

    override fun getDouble(path: String): Double {
        return config.getDouble(path)
    }

    override fun getDouble(path: String, defaultValue: Double): Double {
        return config.getDouble(path, defaultValue)
    }

    override fun getStringList(path: String): List<String> {
        return config.getStringList(path)
    }

    override fun set(path: String, value: Any) {
        config.set(path, value)
    }

    override fun contains(path: String): Boolean {
        return config.contains(path)
    }

    override fun getSection(path: String): ConfigurationSection? {
        return config.getConfigurationSection(path)
    }

    override fun getKeys(deep: Boolean): Set<String> {
        return config.getKeys(deep)
    }
}