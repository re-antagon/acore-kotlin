package org.antagon.acore.core

import org.antagon.acore.Acore
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.reflections.Reflections
import org.reflections.scanners.Scanners

interface AcoreModule {

    /**
     * Display name of the module used for logging.
     */
    val name: String

    /**
     * Checks if this module should be enabled based on configuration or dependencies.
     */
    fun shouldEnable(): Boolean

    /**
     * Enables the module (e.g. registers listeners, commands, or tasks).
     */
    fun enable()

    /**
     * Disables the module (e.g. unregisters listeners or cancels tasks).
     */
    fun disable() {
        if (this is Listener) {
            HandlerList.unregisterAll(this)
        }
    }

    /**
     * Helper to register this or another listener.
     */
    fun registerEvents(plugin: Plugin, listener: Listener = this as Listener) {
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }

    companion object {
        private val reflections = Reflections("org.antagon.acore.feature")
        private val loadedModules = mutableListOf<AcoreModule>()

        /**
         * Unregisters existing event listeners and auto-discovers/enables modules via reflection.
         */
        fun reloadModules(plugin: Acore) {
            disableAll(plugin)

            val subTypes = reflections.getSubTypesOf(AcoreModule::class.java)

            for (clazz in subTypes) {
                try {
                    val instance = clazz.getDeclaredConstructor().newInstance() as AcoreModule
                    if (instance.shouldEnable()) {
                        instance.enable()
                        loadedModules.add(instance)
                        plugin.logger.info("${instance.name} feature enabled")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to load module ${clazz.simpleName}: ${e.message}")
                }
            }
        }

        /**
         * Disables all active modules and unregisters event listeners.
         */
        fun disableAll(plugin: Acore) {
            HandlerList.unregisterAll(plugin)
            for (module in loadedModules.reversed()) {
                try {
                    module.disable()
                } catch (e: Exception) {
                    plugin.logger.warning("Error disabling module ${module.name}: ${e.message}")
                }
            }
            loadedModules.clear()
        }
    }
}
