package org.antagon.acore.core

import org.antagon.acore.Acore
import org.antagon.acore.feature.anvil.AnvilFallListener
import org.antagon.acore.feature.anvil.PistonLaunchAnvilListener
import org.antagon.acore.feature.blocks.BlockBurnListener
import org.antagon.acore.feature.blocks.CopperOxidationListener
import org.antagon.acore.feature.blocks.LightningConversionListener
import org.antagon.acore.feature.blocks.SoulSoilTillListener
import org.antagon.acore.feature.blocks.StonecutterBlockProcessorListener
import org.antagon.acore.feature.coreprotect.CoreProtectVisualizerListener
import org.antagon.acore.feature.economy.CoinTransferFeeListener
import org.antagon.acore.feature.entity.EntityKillListener
import org.antagon.acore.feature.entity.VillagerTransportListener
import org.antagon.acore.feature.items.BannerHeadListener
import org.antagon.acore.feature.items.ItemFrameListener
import org.antagon.acore.feature.items.MinecartSpeedListener
import org.antagon.acore.feature.items.MinecartDamageListener
import org.antagon.acore.feature.items.MultishotCrossbowListener
import org.antagon.acore.feature.leash.LeashPlayersModule
import org.antagon.acore.feature.physicsgun.PhysicsGunModule
import org.antagon.acore.feature.player.BlockInteractionListener
import org.antagon.acore.feature.player.FogPotionListener
import org.antagon.acore.feature.player.IndicatorPotionListener
import org.antagon.acore.feature.player.PlayerJoinListener
import org.antagon.acore.feature.player.SessionDataListener
import org.antagon.acore.feature.player.XaeroFairPlayListener
import org.antagon.acore.feature.referral.ReferralListener
import org.antagon.acore.feature.streak.StreakListener
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

    companion object {
        private val loadedModules = mutableListOf<AcoreModule>()

        /**
         * List of all available module factories.
         */
        private val moduleFactories: List<() -> AcoreModule> = listOf(
            { AnvilFallListener() },
            { PistonLaunchAnvilListener() },
            { BlockBurnListener() },
            { CopperOxidationListener() },
            { LightningConversionListener() },
            { SoulSoilTillListener() },
            { StonecutterBlockProcessorListener() },
            { CoreProtectVisualizerListener() },
            { CoinTransferFeeListener() },
            { EntityKillListener() },
            { VillagerTransportListener() },
            { BannerHeadListener() },
            { ItemFrameListener() },
            { MinecartSpeedListener() },
            { MinecartDamageListener() },
            { MultishotCrossbowListener() },
            { LeashPlayersModule() },
            { PhysicsGunModule() },
            { BlockInteractionListener() },
            { FogPotionListener() },
            { IndicatorPotionListener() },
            { PlayerJoinListener() },
            { SessionDataListener() },
            { XaeroFairPlayListener() },
            { ReferralListener() },
            { StreakListener() }
        )

        /**
         * Unregisters existing event listeners and enables modules.
         */
        fun reloadModules(plugin: Acore) {
            disableAll(plugin)

            for (factory in moduleFactories) {
                try {
                    val instance = factory()
                    if (instance.shouldEnable()) {
                        instance.enable()
                        loadedModules.add(instance)
                        plugin.logger.info("${instance.name} feature enabled")
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to load module: ${e.message}")
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
