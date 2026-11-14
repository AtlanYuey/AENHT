package com.atlanyuey.aenht.core

import android.content.Context
import com.atlanyuey.aenht.shared.translate.LlmService
import com.atlanyuey.aenht.shared.translate.LlmTestResult
import com.atlanyuey.aenht.shared.translate.LlmUsage
import com.atlanyuey.aenht.shared.translate.LlmUsageLedger
import com.atlanyuey.aenht.shared.translate.TranslationException
import com.atlanyuey.aenht.shared.translate.Translation
import com.atlanyuey.aenht.shared.translate.TranslationKind
import com.atlanyuey.aenht.shared.translate.Translator
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * Translation of titles and comments: the user's language models in
 * order, then Microsoft Translator with the user's key (see the shared
 * Translator), then the device's own (ML Kit). Keys stay in the Android Keystore; the list of services is
 * stored with the settings without keys.
 */
object TranslationService {
    private val secrets = SecureStore("aenht.translation")
    private val translator = Translator()
    private val cache = HashMap<String, Translation>()

    private lateinit var prefs: android.content.SharedPreferences
    /** Bumped when services, keys or usage change (screens read it). */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        secrets.init(context, "translation_keys")
        prefs = context.getSharedPreferences("translation", Context.MODE_PRIVATE)
        translator.ledger = LlmUsageLedger.decode(prefs.getString("llmUsage", null))
    }

    private fun saveUsage() {
        if (::prefs.isInitialized) prefs.edit().putString("llmUsage", translator.ledger.encode()).apply()
        revision++
    }

    /** Adds or updates a service and its key (an empty key removes it). */
    fun store(service: LlmService, key: String) {
        if (key.isNotBlank()) secrets[service.id] = key.trim() else secrets[service.id] = null
        val list = services.toMutableList()
        val i = list.indexOfFirst { it.id == service.id }
        if (i >= 0) list[i] = service else list += service
        save(list)
    }

    fun key(id: String): String = secrets[id] ?: ""

    fun usage(service: LlmService): LlmUsage { revision; return translator.usage(service) }
    fun overLimit(service: LlmService): Boolean { revision; return translator.overLimit(service) }

    fun resetUsage(service: LlmService) {
        translator.ledger.reset(service.id)
        saveUsage()
    }

    suspend fun models(baseUrl: String, key: String): List<String> = try {
        translator.models(baseUrl, key)
    } catch (e: TranslationException) {
        throw NHException(e.message ?: L10n.string("Couldn't connect to the server."))
    }

    suspend fun publishedPrice(baseUrl: String, key: String, model: String): List<Double>? = translator.publishedPrice(baseUrl, key, model)

    /** The target language (the app's): test samples leave it out. */
    val targetLanguage: String get() = target

    /** Its name, in the app's language. */
    val targetName: String get() = Locale.forLanguageTag(target).let { it.getDisplayName(it) }

    /** Translates [text] with the service (its usage counts too). */
    suspend fun test(service: LlmService, key: String, text: String): LlmTestResult {
        return try {
            translator.test(service, key, text, target).also { saveUsage() }
        } catch (e: TranslationException) {
            throw NHException(e.message ?: L10n.string("Couldn't translate."))
        }
    }

    val services: List<LlmService> get() = Translator.decodeServices(AppSettings.llmServices)

    private fun save(services: List<LlmService>) {
        AppSettings.llmServices = Translator.encodeServices(services)
        revision++
    }

    fun add(name: String, baseUrl: String, model: String, key: String) {
        val service = LlmService(UUID.randomUUID().toString(), name.trim().ifEmpty { model.trim() }, baseUrl.trim(), model.trim())
        secrets[service.id] = key.trim()
        save(services + service)
    }

    fun remove(id: String) {
        secrets[id] = null
        save(services.filter { it.id != id })
    }

    fun move(id: String, up: Boolean) {
        val list = services.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        save(list)
    }

    fun setEnabled(id: String, enabled: Boolean) = save(services.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun hasKey(id: String) = !secrets[id].isNullOrEmpty()

    var microsoftKey: String?
        get() = secrets["microsoft"]
        set(value) { secrets["microsoft"] = value?.trim() }

    var microsoftRegion: String?
        get() = secrets["microsoftRegion"]
        set(value) { secrets["microsoftRegion"] = value?.trim() }

    val isAvailable: Boolean
        get() = AppSettings.deviceTranslation || services.any { it.enabled } || !microsoftKey.isNullOrEmpty()

    /** The app's language, as the target. */
    private val target: String
        get() {
            val selected = runCatching { AppLanguage.selected(L10n.context) }.getOrDefault("")
            if (selected.isNotEmpty()) return selected
            val l = Locale.getDefault()
            return if (l.language == "zh") (if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans") else l.toLanguageTag()
        }

    suspend fun translate(text: String, kind: TranslationKind): Translation {
        val key = "$target|${kind.name}|$text"
        cache[key]?.let { return it }
        translator.microsoftKey = microsoftKey
        translator.microsoftRegion = microsoftRegion
        val list = services
        val keys = list.mapNotNull { s -> secrets[s.id]?.let { s.id to it } }.toMap()
        return try {
            translator.translate(text, target, kind, list, keys, DeviceTranslator.takeIf { AppSettings.deviceTranslation }).also { cache[key] = it }
        } catch (e: Exception) {
            throw NHException(e.message ?: L10n.string("Couldn't translate."))
        } finally {
            saveUsage()
        }
    }
}
