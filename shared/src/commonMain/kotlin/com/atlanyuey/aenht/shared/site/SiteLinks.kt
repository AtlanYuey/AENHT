package com.atlanyuey.aenht.shared.site

/** A gallery link found in text (clipboard, shared text, deep link). */
sealed class GalleryLink {
    abstract val site: Site

    data class Nh(val id: Int) : GalleryLink() {
        override val site get() = Site.NHENTAI
    }

    /** A gallery link of E-Hentai or ExHentai ([exHentai]). */
    data class Eh(val gid: Long, val token: String, val exHentai: Boolean) : GalleryLink() {
        override val site get() = Site.EHENTAI
    }

    /** An image page link: the gallery token has to be asked for (`EhApi.galleryToken`). */
    data class EhPage(val gid: Long, val pageToken: String, val page: Int, val exHentai: Boolean) : GalleryLink() {
        override val site get() = Site.EHENTAI
    }
}

object SiteLinks {
    private val nh = Regex("""(?:https?://)?(?:www\.)?nhentai\.(?:net|to)/g/(\d+)""", RegexOption.IGNORE_CASE)
    private val eh = Regex("""(?:https?://)?(?:www\.)?(e-hentai|exhentai)\.org/g/(\d+)/([0-9a-f]{10})""", RegexOption.IGNORE_CASE)
    private val ehPage = Regex("""(?:https?://)?(?:www\.)?(e-hentai|exhentai)\.org/s/([0-9a-f]{10})/(\d+)-(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * The match is a link of the site itself, not part of another link:
     * nothing before it that would make the host another one
     * ("evilnhentai.net", "x.nhentai.net", "evil.com/nhentai.net/…",
     * "user@nhentai.net", "https://evil.com/x/https://nhentai.net/…").
     *
     * The text right before the match back to a space, a bracket, a quote
     * or a non-ASCII character (Chinese right next to a link is fine) is the
     * rest of the same word: when it holds a "/", "." or "@" the match sits
     * inside another address.
     */
    private fun atHostStart(text: String, match: MatchResult): Boolean {
        var start = match.range.first
        while (start > 0 && !separates(text[start - 1])) start--
        val prefix = text.substring(start, match.range.first)
        if (prefix.any { it == '/' || it == '.' || it == '@' || it == '\\' }) return false
        // Without a scheme, a letter or digit right before makes another host ("evilnhentai.net").
        if (match.value.startsWith("http", ignoreCase = true)) return true
        val before = prefix.lastOrNull() ?: return true
        return !(before in 'a'..'z' || before in 'A'..'Z' || before in '0'..'9' || before in "_-")
    }

    /** Ends a word of a link: space, brackets, quotes and punctuation that links don't use, or any non-ASCII. */
    private fun separates(c: Char): Boolean = c.isWhitespace() || c.code > 0x7E || c in "\"'<>()[]{}|`,;!"

    private fun first(regex: Regex, text: String): MatchResult? = regex.findAll(text).firstOrNull { atHostStart(text, it) }

    /** A gallery number of nhentai: positive, at most nine digits. */
    private fun nhId(digits: String): Int? = digits.takeIf { it.length <= 9 }?.toIntOrNull()?.takeIf { it > 0 }

    /** The first gallery link in [text], or null. Never throws, whatever the text. */
    fun find(text: String): GalleryLink? {
        val candidates = listOfNotNull(
            first(nh, text)?.let { m -> nhId(m.groupValues[1])?.let { m.range.first to GalleryLink.Nh(it) } },
            first(eh, text)?.let { m ->
                val gid = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return@let null
                m.range.first to GalleryLink.Eh(gid, m.groupValues[3].lowercase(), m.groupValues[1].equals("exhentai", true))
            },
            first(ehPage, text)?.let { m ->
                val gid = m.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: return@let null
                val page = m.groupValues[4].toIntOrNull()?.takeIf { it > 0 } ?: return@let null
                m.range.first to GalleryLink.EhPage(gid, m.groupValues[2].lowercase(), page, m.groupValues[1].equals("exhentai", true))
            },
        )
        return candidates.minByOrNull { it.first }?.second
    }

    /**
     * What a search box entry means beyond a search: a bare number is an
     * nhentai gallery; a link is its gallery on either site.
     */
    fun fromSearchText(text: String): GalleryLink? {
        val t = text.trim()
        if (t.isNotEmpty() && t.length <= 9 && t.all { it.isDigit() }) return t.toIntOrNull()?.takeIf { it > 0 }?.let { GalleryLink.Nh(it) }
        return find(t)
    }

    fun url(link: GalleryLink): String = when (link) {
        is GalleryLink.Nh -> "https://nhentai.net/g/${link.id}/"
        is GalleryLink.Eh -> "https://${if (link.exHentai) "exhentai" else "e-hentai"}.org/g/${link.gid}/${link.token}/"
        is GalleryLink.EhPage -> "https://${if (link.exHentai) "exhentai" else "e-hentai"}.org/s/${link.pageToken}/${link.gid}-${link.page}"
    }
}
