package com.atlanyuey.aenht.shared.translate

import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.net.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * A large language model the user added (OpenAI-compatible chat completions).
 * The API key is kept by the app in the Keychain / Android Keystore, under [id].
 */
@Serializable
data class LlmService(
    val id: String,
    val name: String,
    /** e.g. https://api.openai.com/v1 (…/chat/completions is added). */
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
)

/** What is translated: titles are short and keep names; comments are prose. */
enum class TranslationKind { TITLE, COMMENT }

/** A translation and who made it (a service's name, Microsoft Translator, or the device). */
data class Translation(val text: String, val provider: String, /** An earlier choice failed or refused. */ val fellBack: Boolean)

class TranslationException(message: String, val refused: Boolean = false) : Exception(message)

/**
 * Translates titles and comments. The user's language models are tried in
 * their order; a model that refuses (content filter) or fails is skipped
 * for the next one, a refusal is never retried; then Microsoft Translator
 * (with the user's own key); then the device's own translator (given by
 * the app, free). Like AY-Translate.
 */
class Translator internal constructor(private val http: HttpClient, private val now: () -> Long) {
    constructor() : this(buildHttpClient(), { Clock.System.now().toEpochMilliseconds() })

    /** Microsoft Translator key (and Azure region) the user entered; null: not used. */
    var microsoftKey: String? = null
    var microsoftRegion: String? = null

    @Throws(TranslationException::class, CancellationException::class)
    suspend fun translate(
        text: String,
        target: String,
        kind: TranslationKind,
        services: List<LlmService>,
        keys: Map<String, String>,
        local: LocalTranslator?,
    ): Translation {
        if (text.isBlank()) return Translation(text, "", false)
        var fellBack = false
        var lastError: String? = null
        for (service in services.filter { it.enabled }) {
            val key = keys[service.id] ?: continue
            try {
                return Translation(llm(service, key, text, target, kind), service.name, fellBack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                fellBack = true
            }
        }
        microsoftKey?.takeIf { it.isNotBlank() }?.let { key ->
            try {
                return Translation(microsoft(key, microsoftRegion, text, target), "Microsoft Translator", fellBack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                fellBack = true
            }
        }
        local?.let { l ->
            try {
                l.translate(text, target)?.let { return Translation(it, l.name, fellBack) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
        }
        throw TranslationException(lastError ?: "No translator is available")
    }

    // MARK: Language models

    private fun systemPrompt(target: String, kind: TranslationKind) = when (kind) {
        TranslationKind.TITLE ->
            "You translate titles of comic and doujinshi galleries into ${languageName(target)}. " +
                "Keep circle names, artist names and bracketed tags such as [Chinese] or (C104) as they are when unsure. " +
                "Answer with the translated title only."
        TranslationKind.COMMENT ->
            "You translate user comments of a comic site into ${languageName(target)}. " +
                "Keep the tone, keep links and usernames. Answer with the translation only."
    }

    private suspend fun llm(service: LlmService, key: String, text: String, target: String, kind: TranslationKind): String =
        withContext(Dispatchers.IO) {
            val url = service.baseUrl.trimEnd('/') + "/chat/completions"
            val body = buildJsonObject {
                put("model", service.model)
                put("temperature", 0.2)
                put(
                    "messages",
                    buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", systemPrompt(target, kind)) })
                        add(buildJsonObject { put("role", "user"); put("content", text) })
                    },
                )
            }
            val response = http.post(url) {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val raw = response.bodyAsText()
            val o = runCatching { lenientJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (response.status.value !in 200..299) {
                val message = (o?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content } ?: "HTTP ${response.status.value}"
                val refused = message.contains("content", true) && (message.contains("policy", true) || message.contains("filter", true) || message.contains("safety", true))
                throw TranslationException("${service.name}: $message", refused)
            }
            val choice = (o?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw TranslationException("${service.name}: unexpected answer")
            val message = choice["message"] as? JsonObject
            val refusal = (message?.get("refusal") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.content
            if (!refusal.isNullOrBlank() || finish == "content_filter") {
                throw TranslationException("${service.name} refused", refused = true)
            }
            (message?.get("content") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw TranslationException("${service.name}: empty answer")
        }

    // MARK: Microsoft Translator (the user's own key; free tier available)

    private suspend fun microsoft(key: String, region: String?, text: String, target: String): String = withContext(Dispatchers.IO) {
        val response = http.post("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=${translatorCode(target)}") {
            header("Ocp-Apim-Subscription-Key", key)
            region?.takeIf { it.isNotBlank() }?.let { header("Ocp-Apim-Subscription-Region", it) }
            contentType(ContentType.Application.Json)
            setBody(buildJsonArray { add(buildJsonObject { put("Text", text) }) }.toString())
        }
        if (response.status.value != 200) throw TranslationException("Microsoft Translator: HTTP ${response.status.value}")
        val array = lenientJson.parseToJsonElement(response.bodyAsText()) as? JsonArray
        val translations = (array?.firstOrNull() as? JsonObject)?.get("translations") as? JsonArray
        (translations?.firstOrNull() as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotBlank() }
            ?: throw TranslationException("Microsoft Translator: empty answer")
    }

    companion object {
        /** The app's language code (zh-Hans, ja, pt-BR…) as Microsoft Translator's. */
        fun translatorCode(target: String): String = when {
            target.startsWith("zh-Hant") || target == "zh-TW" || target == "zh-HK" -> "zh-Hant"
            target.startsWith("zh") -> "zh-Hans"
            target == "pt-BR" -> "pt"
            target == "id" -> "id"
            else -> target.substringBefore('-')
        }

        fun languageName(code: String): String = when (translatorCode(code)) {
            "zh-Hans" -> "Simplified Chinese"
            "zh-Hant" -> "Traditional Chinese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese (Brazil)"
            "ru" -> "Russian"
            "vi" -> "Vietnamese"
            "th" -> "Thai"
            "id" -> "Indonesian"
            else -> "English"
        }

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun decodeServices(text: String?): List<LlmService> =
            text?.let { runCatching { json.decodeFromString(ListSerializer(LlmService.serializer()), it) }.getOrNull() } ?: emptyList()

        fun encodeServices(services: List<LlmService>): String = json.encodeToString(ListSerializer(LlmService.serializer()), services)
    }
}

/** The device's own translator (the last fallback), given by the app. */
interface LocalTranslator {
    val name: String

    @Throws(CancellationException::class)
    suspend fun translate(text: String, target: String): String?
}
