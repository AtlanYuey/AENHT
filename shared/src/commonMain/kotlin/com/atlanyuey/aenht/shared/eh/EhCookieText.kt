package com.atlanyuey.aenht.shared.eh

/** E-Hentai's login cookies found in a text. */
data class EhCookies(val memberId: String?, val passHash: String?, val igneous: String?) {
    /** Enough to log in with. */
    val complete: Boolean get() = memberId != null && passHash != null
}

/**
 * Finds E-Hentai's login cookies (ipb_member_id, ipb_pass_hash, igneous) in
 * text copied from a browser: a Cookie header ("a=1; b=2"), an export of a
 * cookie extension (JSON with name and value), a cookies.txt file, rows
 * copied from the developer tools (name, tab, value), or "name: value" lines.
 */
object EhCookieText {
    private val names = listOf("ipb_member_id", "ipb_pass_hash", "igneous")

    // A JSON object (no nesting), and its "name" / "value" members.
    private val jsonObject = Regex("""\{[^{}]*\}""")
    private val jsonName = Regex(""""name"\s*:\s*"([^"]*)"""")
    private val jsonValue = Regex(""""value"\s*:\s*"([^"]*)"""")

    // A name, then = or : or white space (a tab between columns), then its value.
    private val pair = Regex("""(?:^|[^A-Za-z0-9_])(ipb_member_id|ipb_pass_hash|igneous)["']?[ \t]*(?:[=:][ \t]*|[ \t]+)["']?([A-Za-z0-9%]+)""")

    fun find(text: String): EhCookies? {
        if (text.length > 200_000) return null
        val found = HashMap<String, String>()
        fun offer(name: String, raw: String) {
            if (name in found) return
            val value = raw.trim().trim('"', '\'')
            if (valid(name, value)) found[name] = value
        }
        for (obj in jsonObject.findAll(text)) {
            val name = jsonName.find(obj.value)?.groupValues?.get(1) ?: continue
            val value = jsonValue.find(obj.value)?.groupValues?.get(1) ?: continue
            if (name in names) offer(name, value)
        }
        for (match in pair.findAll(text)) offer(match.groupValues[1], match.groupValues[2])
        if (found.isEmpty()) return null
        return EhCookies(found["ipb_member_id"], found["ipb_pass_hash"], found["igneous"])
    }

    /** A value that can be the cookie: an account number, a 32-digit hash, igneous's code. */
    private fun valid(name: String, value: String): Boolean = when (name) {
        "ipb_member_id" -> value.length in 1..12 && value.all { it.isDigit() } && value.trimStart('0').isNotEmpty()
        "ipb_pass_hash" -> value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        // "mystery" (and similar words) is what ExHentai sets when the account has no access.
        "igneous" -> value.length in 8..64 && value.all { it.isLetterOrDigit() } && value.any { it.isDigit() }
        else -> false
    }
}
