package org.antagon.acore.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.*
import java.util.logging.Level
import java.util.logging.Logger

class ConfigFileCreator(private val dataFolder: File, private val logger: Logger) {
    private val configFile: File

    init {
        if (!dataFolder.exists() || !dataFolder.isDirectory) {
            if (dataFolder.mkdirs()) {
                logger.info("Created plugin data folder: ${dataFolder.absolutePath}")
            } else if (!dataFolder.exists()) {
                logger.severe("Failed to create plugin data folder at ${dataFolder.absolutePath}!")
            }
        }

        this.configFile = File(dataFolder, "config.yml")
        createConfigIfAbsent()
    }

    fun getConfigFile(): File {
        return configFile
    }

    fun getDefaultConfig(): FileConfiguration {
        val resourceStream = javaClass.getResourceAsStream("/config.yml")
        if (resourceStream == null) {
            logger.severe("Default configuration resource '/config.yml' not found.")
            return YamlConfiguration()
        }
        return YamlConfiguration.loadConfiguration(InputStreamReader(resourceStream))
    }

    fun loadConfig(configFile: File): FileConfiguration {
        return YamlConfiguration.loadConfiguration(configFile)
    }

    fun saveConfig(config: FileConfiguration, file: File) {
        try {
            config.save(file)
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "Failed to save config file: " + e.message, e)
        }
    }

    private fun createConfigIfAbsent() {
        if (!configFile.exists()) {
            try {
                if (configFile.createNewFile()) {
                    logger.info("Created a new configuration file.")

                    val defaultConfig = getDefaultConfig()
                    saveConfig(defaultConfig, configFile)
                }
            } catch (e: IOException) {
                logger.log(Level.SEVERE, "Failed to create config file: " + e.message, e)
            }
        }
    }
}
