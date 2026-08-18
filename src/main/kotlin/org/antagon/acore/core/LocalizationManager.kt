package org.antagon.acore.core

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.translation.GlobalTranslator
import net.kyori.adventure.translation.Translator
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.text.MessageFormat
import java.util.Locale

class LocalizationManager(
    private val plugin: JavaPlugin,
    private val languageFolder: File,
    private val defaultLanguage: String = "ru-RU"
) : Translator {
    private val translatorKey: Key = Key.key("acore", "translations")
    private val translations: MutableMap<String, YamlConfiguration> = HashMap()
    private val miniMessage: MiniMessage = MiniMessage.miniMessage()
    private var isRegistered = false

    override fun name(): Key = translatorKey

    override fun translate(key: String, locale: Locale): MessageFormat? {
        val raw = getRawOrNull(key, locale) ?: return null
        return try {
            MessageFormat(raw, locale)
        } catch (_: Exception) {
            null
        }
    }

    override fun translate(component: TranslatableComponent, locale: Locale): Component? {
        val key = component.key()
        val raw = getRawOrNull(key, locale) ?: return null

        val args = component.arguments()
        if (args.isEmpty()) {
            return miniMessage.deserialize(raw)
        }

        val resolvers = mutableListOf<TagResolver>()
        args.forEachIndexed { index, arg ->
            resolvers.add(Placeholder.component(index.toString(), arg.asComponent()))
        }

        return miniMessage.deserialize(raw, TagResolver.resolver(resolvers))
    }

    fun registerTranslator() {
        if (!isRegistered) {
            GlobalTranslator.translator().addSource(this)
            isRegistered = true
        }
    }

    fun unregisterTranslator() {
        if (isRegistered) {
            GlobalTranslator.translator().removeSource(this)
            isRegistered = false
        }
    }

    fun loadLanguages() {
        if (!languageFolder.exists()) {
            languageFolder.mkdirs()
        }

        // Save default bundles if they do not exist
        for (bundle in listOf("ru-RU.yml", "en-US.yml")) {
            val file = File(languageFolder, bundle)
            if (!file.exists()) {
                try {
                    plugin.saveResource("language/$bundle", false)
                } catch (e: Exception) {
                    plugin.logger.warning("Could not save default language/$bundle: ${e.message}")
                }
            }
        }

        translations.clear()
        val files = languageFolder.listFiles { _, name -> name.endsWith(".yml") } ?: emptyArray()
        for (file in files) {
            val langCode = file.name.removeSuffix(".yml")
            translations[langCode] = YamlConfiguration.loadConfiguration(file)
        }

        registerTranslator()
    }

    fun hasKey(key: String, language: String = defaultLanguage): Boolean {
        val langConfig = translations[language]
            ?: translations[defaultLanguage]
            ?: translations["ru-RU"]
            ?: translations["en-US"]
        return langConfig?.contains(key) == true
    }

    fun hasKey(key: String, locale: Locale?): Boolean {
        return hasKey(key, resolveLanguage(locale))
    }

    fun getRawOrNull(key: String, locale: Locale?): String? {
        val lang = resolveLanguage(locale)
        val langConfig = translations[lang]
            ?: translations[defaultLanguage]
            ?: translations["ru-RU"]
            ?: translations["en-US"]
        return langConfig?.getString(key)
    }

    /**
     * Resolves the matching language code for a given Locale.
     * Matches exact (e.g. ru-RU, ru_RU), language code (e.g. ru), or falls back to defaultLanguage.
     */
    fun resolveLanguage(locale: Locale?): String {
        if (locale == null) return defaultLanguage

        val tag = locale.toLanguageTag() // e.g. "ru-RU"
        if (translations.containsKey(tag)) return tag

        val underscore = "${locale.language}_${locale.country}" // e.g. "ru_RU"
        if (translations.containsKey(underscore)) return underscore

        val dash = "${locale.language}-${locale.country}" // e.g. "ru-RU"
        if (translations.containsKey(dash)) return dash

        val langOnly = locale.language // e.g. "ru"
        val matched = translations.keys.firstOrNull { it.startsWith(langOnly, ignoreCase = true) }
        if (matched != null) return matched

        return defaultLanguage
    }

    /**
     * Resolves the language code for a command sender or player.
     * If the sender is a Player, their client locale is used first.
     */
    fun resolveLanguage(sender: CommandSender?): String {
        return if (sender is Player) {
            resolveLanguage(sender.locale())
        } else {
            defaultLanguage
        }
    }

    fun getRaw(key: String, language: String = defaultLanguage): String {
        val langConfig = translations[language] 
            ?: translations[defaultLanguage] 
            ?: translations["ru-RU"] 
            ?: translations["en-US"]
        return langConfig?.getString(key, key) ?: key
    }

    fun getRaw(key: String, locale: Locale?): String {
        return getRaw(key, resolveLanguage(locale))
    }

    fun getRaw(key: String, sender: CommandSender?): String {
        return getRaw(key, resolveLanguage(sender))
    }

    fun getComponent(
        key: String,
        language: String = defaultLanguage,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        var rawMessage = getRaw(key, language)
        for ((k, v) in placeholders) {
            rawMessage = rawMessage.replace("{$k}", v)
        }
        return miniMessage.deserialize(rawMessage)
    }

    fun getComponent(
        key: String,
        locale: Locale?,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return getComponent(key, resolveLanguage(locale), placeholders)
    }

    fun getComponent(
        key: String,
        sender: CommandSender?,
        placeholders: Map<String, String> = emptyMap()
    ): Component {
        return getComponent(key, resolveLanguage(sender), placeholders)
    }

    fun send(audience: Audience, key: String, placeholders: Map<String, String> = emptyMap()) {
        if (placeholders.isEmpty() && hasKey(key, defaultLanguage)) {
            audience.sendMessage(Component.translatable(key))
        } else {
            val sender = audience as? CommandSender
            val component = getComponent(key, sender, placeholders)
            audience.sendMessage(component)
        }
    }

    fun sendConsole(key: String, placeholders: Map<String, String> = emptyMap(), withPrefix: Boolean = true) {
        val prefix = if (withPrefix) getRaw("prefix", defaultLanguage) + " " else ""
        var rawMessage = prefix + getRaw(key, defaultLanguage)
        for ((k, v) in placeholders) {
            rawMessage = rawMessage.replace("{$k}", v)
        }
        org.bukkit.Bukkit.getConsoleSender().sendMessage(miniMessage.deserialize(rawMessage))
    }

    fun translate(key: String, language: String = defaultLanguage): String {
        return getRaw(key, language)
    }
}

