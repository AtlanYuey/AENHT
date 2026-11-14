package com.atlanyuey.aenht.shared.site

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What a filter rule looks at. */
enum class FilterTarget(val key: String) {
    /** A tag by name, whatever its namespace (nhentai `tag:` = E-Hentai `female:` / `male:` …). */
    TAG("tag"),
    TITLE("title"),
    UPLOADER("uploader"),
    /** The text of a comment (hides the comment, not the gallery). */
    COMMENT("comment"),
    /** Who wrote a comment (hides the comment). */
    COMMENTER("commenter");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: TAG
    }
}

/** A rule that hides galleries of both sites from lists. */
@Serializable
data class FilterRule(
    val id: String,
    val target: String,
    val pattern: String,
    /** [pattern] is a regular expression (otherwise: contains, ignoring case; tags: equal name). */
    val regex: Boolean = false,
    val enabled: Boolean = true,
) {
    val filterTarget: FilterTarget get() = FilterTarget.fromKey(target)
}

/**
 * Hides galleries from browsing lists (home, search) with the user's rules,
 * the same rules on both sites, and with nhentai's account blacklist.
 */
class ContentFilter(rules: List<FilterRule> = emptyList(), nhBlacklist: Set<Int> = emptySet()) {
    private val rules = rules.filter { it.enabled && it.pattern.isNotBlank() }
    private val nhBlacklist = nhBlacklist
    private val regexes = this.rules.filter { it.regex }.associate { rule ->
        rule.id to runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    val isEmpty: Boolean get() = rules.isEmpty() && nhBlacklist.isEmpty()

    /**
     * Whether [card] is hidden. [tagNames] are the gallery's tag names
     * (E-Hentai: its card tags; nhentai: names of its tag ids, from the
     * app's tag data).
     */
    fun hides(card: GallerySummary, tagNames: List<String>): Boolean {
        if (card.site == Site.NHENTAI && card.nhTagIds.any { it in nhBlacklist }) return true
        return rules.any { rule -> rule.filterTarget != FilterTarget.COMMENT && rule.filterTarget != FilterTarget.COMMENTER && matches(rule, card, tagNames) }
    }

    /** Whether a comment by [author] saying [text] is hidden (comment and commenter rules). */
    fun hidesComment(author: String, text: String): Boolean = rules.any { rule ->
        val value = when (rule.filterTarget) {
            FilterTarget.COMMENT -> text
            FilterTarget.COMMENTER -> author
            else -> return@any false
        }
        if (rule.regex) regexes[rule.id]?.containsMatchIn(value) == true
        else if (rule.filterTarget == FilterTarget.COMMENTER) value.equals(rule.pattern.trim(), ignoreCase = true)
        else value.contains(rule.pattern.trim(), ignoreCase = true)
    }

    private fun matches(rule: FilterRule, card: GallerySummary, tagNames: List<String>): Boolean {
        val values = when (rule.filterTarget) {
            FilterTarget.TAG -> tagNames.map { it.substringAfter(':') }
            FilterTarget.TITLE -> listOf(card.title)
            FilterTarget.UPLOADER -> listOfNotNull(card.uploader)
            FilterTarget.COMMENT, FilterTarget.COMMENTER -> return false
        }
        if (rule.regex) {
            val r = regexes[rule.id] ?: return false
            return values.any { r.containsMatchIn(it) }
        }
        val p = rule.pattern.trim().removeSuffix("$").trim('"')
        return when (rule.filterTarget) {
            FilterTarget.TAG -> values.any { it.equals(p.substringAfter(':'), ignoreCase = true) }
            else -> values.any { it.contains(p, ignoreCase = true) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): List<FilterRule> =
            text?.let { runCatching { json.decodeFromString(ListSerializer(FilterRule.serializer()), it) }.getOrNull() } ?: emptyList()

        fun encode(rules: List<FilterRule>): String = json.encodeToString(ListSerializer(FilterRule.serializer()), rules)
    }
}
