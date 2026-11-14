package com.atlanyuey.aenht.shared.translate

import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.net.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
    /** [LlmPreset.key] of the service it was set up from. */
    val preset: String = "other",
    /** Price per million tokens, in [currency]; null when not entered. */
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    /** "USD" or "CNY". */
    val currency: String = "USD",
    /** Monthly spending limit in [currency]; null: no limit. Past it, the next service takes over. */
    val spendLimit: Double? = null,
) {
    val presetKind: LlmPreset get() = LlmPreset.of(preset)
    val currencySymbol: String get() = if (currency == "CNY") "¥" else "$"

    fun cost(input: Long, output: Long): Double =
        ((inputPrice ?: 0.0) * input + (outputPrice ?: 0.0) * output) / 1_000_000.0

    /** A copy with other settings (for Swift, which can't use copy with defaults). */
    fun with(
        name: String, baseUrl: String, model: String, enabled: Boolean,
        inputPrice: Double?, outputPrice: Double?, currency: String, spendLimit: Double?,
    ): LlmService = copy(
        name = name, baseUrl = baseUrl, model = model, enabled = enabled,
        inputPrice = inputPrice, outputPrice = outputPrice, currency = currency, spendLimit = spendLimit,
    )

    companion object {
        fun create(id: String, preset: LlmPreset): LlmService =
            LlmService(id, preset.displayName, preset.baseUrl, preset.suggestedModel, true, preset.key, currency = preset.defaultCurrency)
    }
}

/** A test of a service: its answer and how long it took. */
data class LlmTestResult(val text: String, val millis: Long, val inputTokens: Long, val outputTokens: Long)

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

    /** Usage of the services; the app saves it ([LlmUsageLedger.encode]) after translating. */
    var ledger = LlmUsageLedger()

    /** The calendar month of now, "2026-11" (UTC; a spending limit's period). */
    private fun month(): String = monthOf(now())

    /** The service reached its monthly spending limit: the next one is used. */
    fun overLimit(service: LlmService): Boolean {
        val limit = service.spendLimit ?: return false
        return ledger.usage(service.id, month()).monthCost >= limit
    }

    fun usage(service: LlmService): LlmUsage = ledger.usage(service.id, month())

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
            if (overLimit(service)) {
                lastError = "${service.name}: spending limit reached"
                fellBack = true
                continue
            }
            try {
                val answer = llm(service, key, text, target, kind)
                ledger.record(service.id, month(), answer.input, answer.output, service.cost(answer.input, answer.output))
                return Translation(answer.text, service.name, fellBack)
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

    private class Answer(val text: String, val input: Long, val output: Long)

    /** Translates a sample with [service] (Settings › Translation › Test). */
    @Throws(TranslationException::class, CancellationException::class)
    suspend fun test(service: LlmService, key: String, text: String, target: String): LlmTestResult {
        val start = now()
        val answer = llm(service, key, text, target, TranslationKind.COMMENT)
        ledger.record(service.id, month(), answer.input, answer.output, service.cost(answer.input, answer.output))
        return LlmTestResult(answer.text, now() - start, answer.input, answer.output)
    }

    /** The models the service offers (GET …/models), sorted. */
    @Throws(TranslationException::class, CancellationException::class)
    suspend fun models(baseUrl: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val response = try {
            http.get(baseUrl.trimEnd('/') + "/models") { if (key.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $key") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(e.message ?: "Network error")
        }
        if (response.status.value !in 200..299) throw TranslationException("HTTP ${response.status.value}")
        val o = runCatching { lenientJson.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
        val list = (o?.get("data") ?: o?.get("models")) as? JsonArray ?: throw TranslationException("unexpected answer")
        list.mapNotNull { m -> ((m as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.removePrefix("models/") }.distinct().sorted()
    }

    /**
     * The price per million tokens a service publishes in its model list
     * (OpenRouter does), as input to output; null when it doesn't.
     */
    @Throws(CancellationException::class)
    suspend fun publishedPrice(baseUrl: String, key: String, model: String): List<Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.get(baseUrl.trimEnd('/') + "/models") { if (key.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $key") }
            val o = lenientJson.parseToJsonElement(response.bodyAsText()) as JsonObject
            val entry = (o["data"] as JsonArray).map { it as JsonObject }.first { (it["id"] as? JsonPrimitive)?.content == model }
            val pricing = entry["pricing"] as JsonObject
            val input = (pricing["prompt"] as JsonPrimitive).content.toDouble() * 1_000_000
            val output = (pricing["completion"] as JsonPrimitive).content.toDouble() * 1_000_000
            listOf(input, output)
        }.getOrNull()
    }

    private suspend fun llm(service: LlmService, key: String, text: String, target: String, kind: TranslationKind): Answer =
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
            val content = (message?.get("content") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw TranslationException("${service.name}: empty answer")
            val usage = o["usage"] as? JsonObject
            fun tokens(name: String) = ((usage?.get(name) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L).coerceAtLeast(0)
            Answer(content, tokens("prompt_tokens"), tokens("completion_tokens"))
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
        /** "yyyy-MM" of epoch milliseconds (UTC). */
        fun monthOf(millis: Long): String {
            // Days to civil date (H. Hinnant's algorithm).
            val z = millis.floorDiv(86_400_000L) + 719_468
            val era = z.floorDiv(146_097)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return "$y-${m.toString().padStart(2, '0')}"
        }

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
