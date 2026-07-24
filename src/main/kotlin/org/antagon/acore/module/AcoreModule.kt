package org.antagon.acore.module

import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

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
}
