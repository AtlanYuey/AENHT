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
        assertTrue(seen.none { it.startsWith("https://c.example") }) // no key: skipped

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
}
