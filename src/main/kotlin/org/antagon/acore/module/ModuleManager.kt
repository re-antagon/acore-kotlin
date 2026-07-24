package org.antagon.acore.module

import org.antagon.acore.Acore
import java.util.logging.Logger

class ModuleManager(private val plugin: Acore) {
    private val logger: Logger = plugin.logger
    private val registeredModules = mutableListOf<AcoreModule>()
    private val activeModules = mutableListOf<AcoreModule>()

    fun registerModule(module: AcoreModule): ModuleManager {
        registeredModules.add(module)
        return this
    }

    fun registerModules(vararg modules: AcoreModule): ModuleManager {
        for (module in modules) {
            registerModule(module)
        }
        return this
    }

    fun loadModules() {
        for (module in registeredModules) {
            try {
                if (module.shouldEnable()) {
                    module.enable()
                    activeModules.add(module)
                    logger.info("${module.name} feature enabled")
                }
            } catch (e: Exception) {
                logger.severe("Failed to enable module ${module.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun disableModules() {
        for (module in activeModules.reversed()) {
            try {
                module.disable()
            } catch (e: Exception) {
                logger.severe("Error disabling module ${module.name}: ${e.message}")
            }
        }
        activeModules.clear()
    }

    fun reloadModules() {
        disableModules()
        loadModules()
    }
}
