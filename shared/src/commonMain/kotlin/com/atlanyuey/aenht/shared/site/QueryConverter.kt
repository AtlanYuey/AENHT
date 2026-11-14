package com.atlanyuey.aenht.shared.site

import com.atlanyuey.aenht.shared.eh.EhCategory

/**
 * Converts a search between the two sites' syntaxes when the user switches
 * site in the search results.
 *
 * nhentai: `tag:"big eyes" -artist:x language:chinese category:manga words`
 * E-Hentai: `female:"big eyes$" -artist:x$ language:chinese$ words` (+ category filter)
 */
object QueryConverter {
    data class Result(
        val query: String,
        /** E-Hentai categories to show (from nhentai's `category:`); empty = all. */
        val categories: Set<EhCategory> = emptySet(),
        /** Terms that have no counterpart on the other site and were left out. */
        val dropped: List<String> = emptyList(),
    )

    private data class Term(val negated: Boolean, val namespace: String?, val value: String)

    private val termRegex = Regex("""(-?)(?:([a-zA-Z]+):)?("([^"]*)"|\S+)""")

    private fun terms(query: String): List<Term> = termRegex.findAll(query).map { m ->
        val quoted = m.groupValues[4]
        val raw = if (m.groupValues[3].startsWith("\"")) quoted else m.groupValues[3]
        Term(m.groupValues[1] == "-", m.groupValues[2].lowercase().ifEmpty { null }, raw.removeSuffix("$").trim())
    }.filter { it.value.isNotEmpty() }.toList()

    private fun quote(value: String, exact: Boolean): String {
        val v = if (exact) "$value$" else value
        return if (value.contains(' ')) "\"$v\"" else v
    }

    private val shared = setOf("artist", "group", "parody", "character", "language")
    private val ehGeneral = setOf("female", "male", "mixed", "other")
    private val ehShort = mapOf("f" to "female", "m" to "male", "x" to "mixed", "o" to "other", "a" to "artist", "g" to "group", "p" to "parody", "c" to "character", "l" to "language")

    /**
     * nhentai → E-Hentai. [ehNamespaces] tells which E-Hentai namespaces
     * have a general tag (from the tag translation data), e.g.
     * "glasses" → [female, male]; one match is used as is, several (or none)
     * search the tag without a namespace.
     */
    fun nhToEh(query: String, ehNamespaces: (String) -> List<String> = { emptyList() }): Result {
        val out = mutableListOf<String>()
        val categories = mutableSetOf<EhCategory>()
        val dropped = mutableListOf<String>()
        for (t in terms(query)) {
            val sign = if (t.negated) "-" else ""
            when (t.namespace) {
                null -> out += sign + quote(t.value, exact = false)
                "tag", "tags" -> {
                    val ns = ehNamespaces(t.value).filter { it in ehGeneral }.distinct()
                    out += sign + (if (ns.size == 1) "${ns[0]}:" else "") + quote(t.value, exact = true)
                }
                in shared -> out += sign + "${t.namespace}:" + quote(t.value, exact = true)
                "category", "categories" -> {
                    val cat = EhCategory.fromLabel(t.value.replace("artistcg", "artist cg").replace("gamecg", "game cg"))
                    if (cat != null && !t.negated) categories += cat else dropped += "${sign}category:${t.value}"
                }
                else -> dropped += "$sign${t.namespace}:${t.value}"
            }
        }
        return Result(out.joinToString(" "), categories, dropped)
    }

    /** E-Hentai → nhentai (categories other than the query are not carried over). */
    fun ehToNh(query: String): Result {
        val out = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        for (t in terms(query)) {
            val sign = if (t.negated) "-" else ""
            val ns = t.namespace?.let { ehShort[it] ?: it }
            val value = if (t.value.contains(' ')) "\"${t.value}\"" else t.value
            when (ns) {
                null -> out += sign + value
                in ehGeneral -> out += "${sign}tag:$value"
                in shared -> out += "$sign$ns:$value"
                else -> dropped += "$sign${t.namespace}:${t.value}"
            }
        }
        return Result(out.joinToString(" "), dropped = dropped)
    }
}
