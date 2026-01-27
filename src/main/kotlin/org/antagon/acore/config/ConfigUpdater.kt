package org.antagon.acore.config

import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class ConfigUpdater(private val logger: Logger) {
    companion object {
        private const val REQUIRED_VERSION = 1
    }

    fun updateConfiguration(config: FileConfiguration, defaultConfig: FileConfiguration, configFile: File): FileConfiguration {
        val currentVersion = config.getInt("config-version", 0)
        if (currentVersion < REQUIRED_VERSION) {
            logger.info("Updating configuration to version $REQUIRED_VERSION")
            config.set("config-version", REQUIRED_VERSION)
        }

        DefaultsMerger.mergeDefaults(defaultConfig, config, logger)

        try {
            config.save(configFile)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to save updated configuration: " + e.message, e)
        }

        return config
    }
}
