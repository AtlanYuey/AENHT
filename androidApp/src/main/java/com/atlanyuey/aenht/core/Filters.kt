package com.atlanyuey.aenht.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.shared.site.ContentFilter
import com.atlanyuey.aenht.shared.site.FilterRule
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The user's filter rules (both sites, stored with the settings) and the
 * nhentai account's blacklist, applied to browsing lists.
 */
object Filters {
    private var nhBlacklist by mutableStateOf<Set<Int>>(emptySet())
    private var lastBlacklistLoad = 0L

    val rules: List<FilterRule> get() = ContentFilter.decode(AppSettings.filterRules)

    private fun save(rules: List<FilterRule>) {
        AppSettings.filterRules = ContentFilter.encode(rules)
    }

    fun add(target: String, pattern: String, regex: Boolean) {
        if (pattern.isBlank()) return
        save(rules + FilterRule(UUID.randomUUID().toString(), target, pattern.trim(), regex))
    }

    fun setEnabled(id: String, enabled: Boolean) = save(rules.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun remove(id: String) = save(rules.filter { it.id != id })

    /** The filter for the current rules (rebuilt when they change). */
    val current: ContentFilter
        get() = ContentFilter(rules, if (AppSettings.useNhBlacklist && Session.isLoggedIn) nhBlacklist else emptySet())

    /** Card tag names: E-Hentai's names, or the names of nhentai's tag ids. */
    fun tagNames(card: GallerySummary): List<String> =
        if (card.site == Site.NHENTAI) card.nhTagIds.mapNotNull { TagStore.tag(it)?.name } else card.cardTags

    fun visible(cards: List<GallerySummary>): List<GallerySummary> {
        val f = current
        if (f.isEmpty) return cards
        return cards.filter { !f.hides(it, tagNames(it)) }
    }

    /** Loads the nhentai blacklist (at most every 30 minutes). */
    fun refreshNhBlacklist(force: Boolean = false) {
        if (!Session.isLoggedIn || !AppSettings.useNhBlacklist) return
        val now = System.currentTimeMillis()
        if (!force && now - lastBlacklistLoad < 30 * 60 * 1000L) return
        lastBlacklistLoad = now
        AppScope.launch {
            runCatching { NHClient.api.blacklistIds() }.getOrNull()?.let { nhBlacklist = it.toSet() }
        }
    }

    val nhBlacklistCount: Int get() = nhBlacklist.size
}
