package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.translate.LlmService
import com.atlanyuey.aenht.shared.translate.LocalTranslator
import com.atlanyuey.aenht.shared.translate.TranslationKind
import com.atlanyuey.aenht.shared.translate.Translator
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslatorTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun refusalFallsToTheNextModelThenMicrosoftThenTheDevice() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            seen += url
            when {
                url.startsWith("https://a.example") -> respond("""{"choices":[{"message":{"content":null,"refusal":"I can't help"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, json)
                url.startsWith("https://b.example") -> respond("""{"error":{"message":"server busy"}}""", HttpStatusCode.ServiceUnavailable, json)
                url.startsWith("https://api.cognitive.microsofttranslator.com/translate") -> {
                    assertEquals("mskey", request.headers["Ocp-Apim-Subscription-Key"])
                    respond("""[{"translations":[{"text":"你好","to":"zh-Hans"}]}]""", HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val t = Translator(buildHttpClient(engine)) { 0L }
        t.microsoftKey = "mskey"
        val services = listOf(
            LlmService("1", "A", "https://a.example/v1", "m"),
            LlmService("2", "B", "https://b.example/v1", "m"),
            LlmService("3", "C (no key)", "https://c.example/v1", "m"),
        )
        val r = t.translate("hello", "zh-Hans", TranslationKind.COMMENT, services, mapOf("1" to "k", "2" to "k"), null)
        assertEquals("你好", r.text)
        assertEquals("Microsoft Translator", r.provider)
        assertTrue(r.fellBack)
        assertTrue(seen.any { it.startsWith("https://c.example") }) // no key: tried too (self-hosted services need none)

        t.microsoftKey = null
        val local = object : LocalTranslator {
            override val name = "Device"
            override suspend fun translate(text: String, target: String) = "本机：$text"
        }
        val r2 = t.translate("hello", "zh-Hans", TranslationKind.TITLE, services, mapOf("1" to "k"), local)
        assertEquals("Device", r2.provider)
    }

    @Test
    fun aModelThatAnswersIsUsed() = runTest {
        val engine = MockEngine { respond("""{"choices":[{"message":{"content":" 夏休み "},"finish_reason":"stop"}]}""", HttpStatusCode.OK, json) }
        val r = Translator(buildHttpClient(engine)) { 0L }.translate(
            "Summer", "ja", TranslationKind.TITLE, listOf(LlmService("1", "A", "https://a.example/v1/", "m")), mapOf("1" to "k"), null,
        )
        assertEquals("夏休み", r.text)
        assertEquals("A", r.provider)
        assertEquals(false, r.fellBack)
        assertEquals("zh-Hant", Translator.translatorCode("zh-Hant-TW"))
        assertEquals("pt", Translator.translatorCode("pt-BR"))
    }

    @Test
    fun requestParametersFollowThePresetAndTheCustomJson() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            respond("""{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, json)
        }
        val t = Translator(buildHttpClient(engine)) { 0L }
        fun sent() = kotlinx.serialization.json.Json.parseToJsonElement(bodies.last()) as kotlinx.serialization.json.JsonObject
        val base = LlmService("1", "A", "https://a.example/v1", "m")

        // Model defaults: nothing added (no temperature either).
        t.translate("ok", "zh-Hans", TranslationKind.COMMENT, listOf(base), mapOf("1" to "k"), null)
        assertEquals(setOf("model", "messages"), sent().keys)

        // A preset, overridden by the custom JSON.
        val custom = base.withRequestOptions("deepSeekFast", """{"temperature":0.2,"thinking":{"type":"enabled"}}""")
        t.translate("ok", "zh-Hans", TranslationKind.COMMENT, listOf(custom), mapOf("1" to "k"), null)
        assertEquals("0.2", sent()["temperature"].toString())
        assertEquals("""{"type":"enabled"}""", sent()["thinking"].toString())

        // Gemini on Google's API: light reasoning unless one was chosen.
        val gemini = LlmService("2", "G", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash")
        t.translate("ok", "zh-Hans", TranslationKind.COMMENT, listOf(gemini), mapOf("2" to "k"), null)
        assertEquals("\"low\"", sent()["reasoning_effort"].toString())
        t.translate("ok", "zh-Hans", TranslationKind.COMMENT, listOf(gemini.withRequestOptions("high", "")), mapOf("2" to "k"), null)
        assertEquals("\"high\"", sent()["reasoning_effort"].toString())

        // Not accepted: not an object, fields the app manages, two token limits.
        assertEquals(false, base.withRequestOptions("custom", "{not json").parametersValid)
        assertEquals(LlmService.ERROR_RESERVED, base.withRequestOptions("custom", """{"model":"x"}""").effectiveParametersText())
        assertEquals(false, base.withRequestOptions("custom", """{"max_tokens":1,"max_completion_tokens":2}""").parametersValid)
        assertEquals("{\n    \"reasoning_effort\": \"low\"\n}", base.withRequestOptions("low", "").effectiveParametersText())
    }

    @Test
    fun usageIsCountedAndTheSpendingLimitHandsOver() = runTest {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith("/models") -> respond("""{"data":[{"id":"b-model","pricing":{"prompt":"0.000001","completion":"0.000002"}},{"id":"a-model"}]}""", HttpStatusCode.OK, json)
                url.startsWith("https://a.example") ->
                    respond("""{"choices":[{"message":{"content":"甲"},"finish_reason":"stop"}],"usage":{"prompt_tokens":600000,"completion_tokens":400000}}""", HttpStatusCode.OK, json)
                else -> respond("""{"choices":[{"message":{"content":"乙"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, json)
            }
        }
        // 2026-11-14
        val t = Translator(buildHttpClient(engine)) { 1_794_960_000_000L }
        assertEquals("2026-11", Translator.monthOf(1_794_960_000_000L))
        assertEquals("1970-01", Translator.monthOf(0L))
        val a = LlmService("1", "A", "https://a.example/v1", "m", inputPrice = 1.0, outputPrice = 2.0, spendLimit = 1.0)
        val b = LlmService("2", "B", "https://b.example/v1", "m")
        val keys = mapOf("1" to "k", "2" to "k")
        assertEquals("甲", t.translate("x", "zh-Hans", TranslationKind.TITLE, listOf(a, b), keys, null).text)
        val used = t.usage(a)
        assertEquals(600_000L, used.monthInput)
        assertEquals(1.4, used.monthCost, 1e-9)
        // Past the limit: B takes over.
        val r = t.translate("y", "zh-Hans", TranslationKind.TITLE, listOf(a, b), keys, null)
        assertEquals("B", r.provider)
        assertTrue(r.fellBack)
        // The ledger survives saving.
        val copy = com.atlanyuey.aenht.shared.translate.LlmUsageLedger.decode(t.ledger.encode())
        assertEquals(1.4, copy.usage("1", "2026-11").totalCost, 1e-9)
        assertEquals(0.0, copy.usage("1", "2026-12").monthCost, 1e-9)

        assertEquals(listOf("a-model", "b-model"), t.models("https://x.example/v1", "k"))
        assertEquals(listOf(1.0, 2.0), t.publishedPrice("https://x.example/v1", "k", "b-model")?.map { kotlin.math.round(it * 1e6) / 1e6 })
    }
}
