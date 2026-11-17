package com.atlanyuey.aenht.shared.net

/**
 * What a failed connection says to the user: one short sentence (an English
 * key the apps translate) instead of the system's error dump. The details go
 * to the diagnostics log where the failure is caught.
 */
object NetFailure {
    const val OFFLINE = "No internet connection."
    const val TIMEOUT = "The connection timed out. Try again."
    const val UNREACHABLE = "Couldn't reach the server. Check your network or proxy."
    const val SECURE = "Couldn't make a secure connection to the server."
    const val OTHER = "The connection failed. Try again."

    val all = listOf(OFFLINE, TIMEOUT, UNREACHABLE, SECURE, OTHER)

    /** The sentence for [e] when it is a failed connection (URLSession's or OkHttp's); null otherwise. */
    fun describeOrNull(e: Throwable): String? {
        val text = generateSequence(e) { it.cause }.take(6)
            .joinToString(" ") { (it::class.simpleName ?: "") + ": " + (it.message ?: "") }
        val lower = text.lowercase()
        // URLSession's error codes, as its description prints them ("Code=-1009").
        val codes = Regex("Code=(-\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        fun code(vararg wanted: Int) = wanted.any { it in codes }
        return when {
            // URLSession: not connected, roaming off, cellular data not allowed. Android: no route.
            code(-1009, -1018, -1020) || "network is unreachable" in lower || "enetunreach" in lower -> OFFLINE
            code(-1001) || "timeout" in lower || "timed out" in lower -> TIMEOUT
            codes.any { it in -1206..-1200 } || "sslexception" in lower || "sslhandshake" in lower ||
                "certificate" in lower || "certpath" in lower -> SECURE
            code(-1003, -1004, -1005, -1006, -1017, -1011) || "unknownhost" in lower || "unable to resolve host" in lower ||
                "connectexception" in lower || "connection refused" in lower || "connection reset" in lower ||
                "failed to connect" in lower || "unexpected end of stream" in lower || "broken pipe" in lower ||
                "nsurlerrordomain" in lower || "ioexception" in lower -> UNREACHABLE
            else -> null
        }
    }

    fun describe(e: Throwable): String = describeOrNull(e) ?: OTHER
}
