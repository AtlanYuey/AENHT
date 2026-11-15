package com.atlanyuey.aenht.shared.app

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/**
 * A short log of what went wrong (requests that failed, translations,
 * sync), kept in memory for this session. Shared from About › Diagnostics,
 * with tokens, cookies and keys taken out.
 */
@OptIn(ExperimentalAtomicApi::class)
object Diagnostics {
    private const val MAX = 400
    private val lines = AtomicReference(emptyList<String>())

    fun log(category: String, message: String) {
        val time = Clock.System.now().toString().substring(0, 19).replace('T', ' ')
        val line = "$time [$category] ${redact(message)}"
        while (true) {
            val current = lines.load()
            val next = (current + line).takeLast(MAX)
            if (lines.compareAndSet(current, next)) return
        }
    }

    fun entries(): List<String> = lines.load()

    fun clear() = lines.store(emptyList())

    private val secrets = listOf(
        // Whole credential headers, whatever the scheme (Basic, Bearer, Key, …).
        Regex("""((?:Proxy-)?Authorization\s*[:=]\s*)[^\r\n]+""", RegexOption.IGNORE_CASE) to "$1•••",
        Regex("""((?:Set-)?Cookie\s*:\s*)[^\r\n]+""", RegexOption.IGNORE_CASE) to "$1•••",
        Regex("""((?:ipb_member_id|ipb_pass_hash|igneous|ipb_session_id|sk|star)=)[^;&\s]+""") to "$1•••",
        // user:password@ in a URL.
        Regex("""(://)[^/\s:@]+:[^/\s@]+@""") to "$1•••@",
        // Secrets as URL or form parameters.
        Regex(
            """((?:^|[?&;\s])(?:api[_-]?key|key|token|access[_-]?token|refresh[_-]?token|id[_-]?token|auth|password|passwd|pwd|secret|sig|signature|session)=)[^&#\s]+""",
            RegexOption.IGNORE_CASE,
        ) to "$1•••",
        Regex(""""(apikey|api_key|api-key|access_token|refresh_token|id_token|token|password|secret|authorization)"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE) to "\"$1\":\"•••\"",
        Regex("""\bsk-[A-Za-z0-9_-]{8,}""") to "sk-•••",
        Regex("""key:[A-Za-z0-9_-]{6,}""") to "key:•••",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "•••@•••",
    )

    /** Takes tokens, cookies, keys and e-mail addresses out of [text]. */
    fun redact(text: String): String = secrets.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    /** The report to share: [header] (app, device, settings) and the log. */
    fun report(header: String): String = buildString {
        append(redact(header).trimEnd()).append("\n\n")
        val all = entries()
        if (all.isEmpty()) append("(nothing went wrong in this session)\n") else all.forEach { append(it).append('\n') }
    }
}
