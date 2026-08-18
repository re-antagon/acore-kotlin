package org.antagon.acore.util

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import java.util.logging.Logger

class MissingDependencyException(
    val dependencyName: String,
    val featureName: String,
    message: String = "Required dependency '$dependencyName' is missing or uninitialized for feature '$featureName'."
) : RuntimeException(message)

object DependencyHandler {
    private val defaultLogger = Logger.getLogger(DependencyHandler::class.java.name)

    /**
     * Checks if a plugin dependency is present and enabled on the server.
     */
    fun isPluginEnabled(pluginName: String): Boolean {
        return try {
            Bukkit.getPluginManager().isPluginEnabled(pluginName)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks if a plugin dependency is present/installed on the server (useful during onLoad before plugins are enabled).
     */
    fun isPluginInstalled(pluginName: String): Boolean {
        return try {
            Bukkit.getPluginManager().getPlugin(pluginName) != null
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Safely retrieves the LuckPerms API instance if available.
     * Returns null if LuckPerms is not installed, disabled, or uninitialized.
     */
    fun getLuckPerms(): LuckPerms? {
        if (!isPluginEnabled("LuckPerms")) return null

        return try {
            LuckPermsProvider.get()
        } catch (e: Throwable) {
            defaultLogger.warning("LuckPerms API is not accessible: ${e.message}")
            null
        }
    }

    /**
     * Executes a code block dependent on an external plugin safely.
     * Intercepts missing dependency errors and logs a formatted warning instead of throwing stack traces.
     */
    fun <T> executeSafely(
        dependencyName: String,
        featureName: String,
        fallback: T? = null,
        logger: Logger? = null,
        checkEnabled: Boolean = true,
        block: () -> T
    ): T? {
        val log = logger ?: defaultLogger
        val isAvailable = if (checkEnabled) isPluginEnabled(dependencyName) else isPluginInstalled(dependencyName)
        if (!isAvailable) {
            log.warning("Dependency '$dependencyName' is missing or disabled. Feature '$featureName' will be skipped.")
            return fallback
        }

        return try {
            block()
        } catch (e: MissingDependencyException) {
            log.warning("Feature '$featureName' skipped: ${e.message}")
            fallback
        } catch (e: NoClassDefFoundError) {
            log.warning("Class missing for dependency '$dependencyName' in feature '$featureName': ${e.message}")
            fallback
        } catch (e: ClassNotFoundException) {
            log.warning("Class not found for dependency '$dependencyName' in feature '$featureName': ${e.message}")
            fallback
        } catch (e: IllegalStateException) {
            log.warning("Dependency '$dependencyName' is not initialized for feature '$featureName': ${e.message}")
            fallback
        } catch (e: Throwable) {
            log.warning("Failed executing feature '$featureName' requiring '$dependencyName': ${e.message}")
            fallback
        }
    }
}
