package com.atlanyuey.aenht.shared.site

import com.atlanyuey.aenht.shared.eh.EhGalleryDetail

/**
 * Other versions of a gallery (its translations, the original, re-scans):
 * galleries whose title is the same once the bracketed parts (circle,
 * event, parody, language, "Digital"…) are taken away. Searched by that
 * title on the same site.
 */
object GalleryVersions {
    private val brackets = listOf(
        Regex("""\[[^\]]*]"""), Regex("""\([^)]*\)"""), Regex("""\{[^}]*}"""),
        Regex("""【[^】]*】"""), Regex("""（[^）]*）"""), Regex("""「[^」]*」"""),
    )

    /** The title without its bracketed parts, e.g. "(C104) [Circle] Title (Parody) [Chinese]" → "Title". */
    fun core(title: String): String {
        var text = title
        repeat(2) { brackets.forEach { text = it.replace(text, " ") } }
        return text.replace(Regex("""\s+"""), " ").trim().trim('-', '|', ':', ' ')
    }

    /** What the search is made with: the core, or the longest of its "Title | Title" parts. */
    fun searchText(title: String): String =
        core(title).split(" | ", "｜").map { it.trim() }.maxByOrNull { it.length }.orEmpty()

    /** Comparable form: lower case, letters and digits only. */
    private fun key(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** The "A | B" alternatives of a title's core, as keys. */
    private fun keys(title: String): Set<String> =
        core(title).split(" | ", "｜").map { key(it) }.filter { it.length >= 3 }.toSet()

    /** Whether [candidate] (a title) is a version of a gallery with these [titles] (e.g. English and Japanese). */
    fun isVersion(titles: List<String>, candidate: String): Boolean {
        val mine = titles.flatMap { keys(it) }.toSet()
        return mine.isNotEmpty() && keys(candidate).any { it in mine }
    }
}

/** Searches for galleries like an E-Hentai gallery (E-Hentai has no list of its own). */
object EhSimilar {
    /**
     * Searches to try in turn, most telling first: the same artist (or
     * group), the same parody and character, then two of its tags.
     */
    fun queries(detail: EhGalleryDetail): List<String> {
        fun tags(namespace: String) = detail.tags[namespace].orEmpty()
        val result = mutableListOf<String>()
        (tags("artist").firstOrNull() ?: tags("group").firstOrNull())?.let { result += it.query }
        val parody = tags("parody").firstOrNull { it.name != "original" }
        if (parody != null) {
            result += listOfNotNull(parody.query, tags("character").firstOrNull()?.query).joinToString(" ")
        }
        val themes = (tags("female") + tags("male") + tags("mixed")).take(2)
        if (themes.size == 2) result += themes.joinToString(" ") { it.query }
        return result.distinct()
    }
}
