package org.antagon.acore.config

import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

class DefaultsMerger {
    companion object {
        fun mergeDefaults(source: FileConfiguration, target: FileConfiguration, logger: Logger) {
            source.getKeys(true).stream()
                .filter { key -> !target.contains(key) }
                .forEach { key ->
                    target.set(key, source.get(key))
                    logger.info("Added missing configuration key: $key = ${source.get(key)}")
                }
        }
    }
}
