package com.atlanyuey.aenht.shared.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Language model services with an OpenAI-compatible API, as in AY Translate:
 * the address is filled in, the model stays editable (and can be read from
 * the service's model list).
 */
enum class LlmPreset(val key: String, val displayName: String, val baseUrl: String, val suggestedModel: String, val defaultCurrency: String) {
    DEEPSEEK("deepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "CNY"),
    GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "USD"),
    OPENAI("openAI", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", "USD"),
    QWEN("qwen", "通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "CNY"),
    SILICONFLOW("siliconFlow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "CNY"),
    MOONSHOT("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1", "kimi-latest", "CNY"),
    ZHIPU("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "CNY"),
    OPENROUTER("openRouter", "OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash", "USD"),
    OTHER("other", "OpenAI-compatible", "https://api.openai.com/v1", "", "USD");

    /** Services that bill in yuan or dollars depending on the account: the user chooses. */
    val choosesCurrency: Boolean get() = this != GEMINI && this != OPENAI && this != OPENROUTER

    companion object {
        fun of(key: String?): LlmPreset = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** Tokens and money one service used: this calendar month (for the spending limit) and in total. */
@Serializable
data class LlmUsage(
    val month: String = "",
    val monthInput: Long = 0,
    val monthOutput: Long = 0,
    val monthCost: Double = 0.0,
    val totalInput: Long = 0,
    val totalOutput: Long = 0,
    val totalCost: Double = 0.0,
)

/**
 * What each service has used. Costs are added at the price when the tokens
 * were used, so a later price change doesn't rewrite the past.
 */
class LlmUsageLedger(private val entries: MutableMap<String, LlmUsage> = mutableMapOf()) {
    /** The entry as of [month] ("2026-11"): a new month starts at zero. */
    fun usage(id: String, month: String): LlmUsage {
        val e = entries[id] ?: return LlmUsage(month = month)
        return if (e.month == month) e else e.copy(month = month, monthInput = 0, monthOutput = 0, monthCost = 0.0)
    }

    fun record(id: String, month: String, input: Long, output: Long, cost: Double) {
        if (input == 0L && output == 0L) return
        val e = usage(id, month)
        entries[id] = e.copy(
            monthInput = e.monthInput + input, monthOutput = e.monthOutput + output, monthCost = e.monthCost + cost,
            totalInput = e.totalInput + input, totalOutput = e.totalOutput + output, totalCost = e.totalCost + cost,
        )
    }

    fun reset(id: String) { entries.remove(id) }

    fun encode(): String = json.encodeToString(Snapshot.serializer(), Snapshot(entries.toMap()))

    @Serializable
    private data class Snapshot(val services: Map<String, LlmUsage> = emptyMap())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): LlmUsageLedger =
            LlmUsageLedger(text?.let { runCatching { json.decodeFromString(Snapshot.serializer(), it) }.getOrNull() }?.services?.toMutableMap() ?: mutableMapOf())
    }
}
