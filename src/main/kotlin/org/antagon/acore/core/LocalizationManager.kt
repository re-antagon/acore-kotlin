package org.antagon.acore.core

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class LocalizationManager(private val languageFolder: File, private val defaultLanguage: String) {
    private val translations: MutableMap<String, YamlConfiguration> = HashMap()

    fun loadLanguages() {
        if (!languageFolder.exists()) languageFolder.mkdirs()
        for (file in languageFolder.listFiles { dir, name -> name.endsWith(".yml") } ?: arrayOf()) {
            val langCode = file.name.replace(".yml", "")
            translations[langCode] = YamlConfiguration.loadConfiguration(file)
        }
    }

    fun translate(key: String, language: String): String {
        val langConfig = translations[language] ?: translations[defaultLanguage]
        return langConfig?.getString(key, key) ?: key
    }
}