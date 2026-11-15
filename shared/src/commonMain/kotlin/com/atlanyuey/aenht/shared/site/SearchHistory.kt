package com.atlanyuey.aenht.shared.site

/**
 * An entry of the search history: the search and the site it was made on
 * (the two sites' search syntax differs). Stored as one string, so the list
 * stays a list of strings in the settings and in sync; entries saved before
 * sites were recorded have no site.
 */
object SearchHistory {
    private const val SEPARATOR = '\t'

    fun entry(site: Site, query: String): String = "${site.key}$SEPARATOR${query.trim()}"

    /** The site of [entry], or null for an entry from before sites were recorded. */
    fun site(entry: String): Site? = entry.substringBefore(SEPARATOR, "").takeIf { it.isNotEmpty() }?.let { Site.fromKey(it) }

    /** The search text of [entry]. */
    fun query(entry: String): String = if (site(entry) != null) entry.substringAfter(SEPARATOR) else entry

    /** [entries] with [entry] first, without its earlier copy (same site and text), at most [limit]. */
    fun adding(entries: List<String>, entry: String, limit: Int): List<String> =
        (listOf(entry) + entries.filter { it != entry && !(site(it) == null && query(it) == query(entry)) }).take(limit)
}
