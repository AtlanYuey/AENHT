package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.FavoriteRecord
import com.atlanyuey.aenht.shared.db.HistoryRecord
import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhToplistPeriod
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.QueryConverter
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.SiteLinks

/**
 * Plain functions for the iOS app: Swift sees Kotlin's sealed classes,
 * default arguments and nested types awkwardly, so it builds them here.
 */
object Bridge {
    // MARK: E-Hentai lists

    /** Front page / search; [categoriesShown] are EhCategory bits to show (0 = all). */
    fun search(query: String, categoriesShown: Int): EhListSource =
        EhListSource.Search(query, EhCategory.entries.filter { categoriesShown and it.bit != 0 }.toSet())

    fun popular(): EhListSource = EhListSource.Popular
    fun watched(query: String): EhListSource = EhListSource.Watched(query)

    /** [folder] 0–9, or -1 for all folders. */
    fun favorites(folder: Int, query: String): EhListSource = EhListSource.Favorites(folder.takeIf { it in 0..9 }, query)

    /** 0 yesterday, 1 month, 2 year, 3 all time. */
    fun toplist(period: Int): EhListSource =
        EhListSource.Toplist(EhToplistPeriod.entries[period.coerceIn(0, EhToplistPeriod.entries.size - 1)])

    fun isToplist(source: EhListSource): Boolean = source is EhListSource.Toplist

    // MARK: Sites and keys

    val nhentai: Site get() = Site.NHENTAI
    val ehentai: Site get() = Site.EHENTAI

    fun site(key: String): Site = Site.fromKey(key) ?: Site.NHENTAI

    fun galleryKey(site: Site, id: Long, token: String?): GalleryKey = GalleryKey(site, id, token)

    // MARK: Links

    /** The first gallery link in [text] as "nh:<id>", "eh:<gid>:<token>" or "ehpage:<gid>:<pagetoken>:<page>". */
    fun findLink(text: String): String? = SiteLinks.find(text)?.let(::linkString)

    /** A number (nhentai) or a link typed in the search box, in the same form. */
    fun searchLink(text: String): String? = SiteLinks.fromSearchText(text)?.let(::linkString)

    private fun linkString(link: GalleryLink): String = when (link) {
        is GalleryLink.Nh -> "nh:${link.id}"
        is GalleryLink.Eh -> "eh:${link.gid}:${link.token}"
        is GalleryLink.EhPage -> "ehpage:${link.gid}:${link.pageToken}:${link.page}"
    }

    // MARK: Search syntax

    /** nhentai → E-Hentai; [namespaces] lists, for each tag name, its E-Hentai namespaces (from the translation data). */
    fun nhToEh(query: String, namespaces: Map<String, List<String>>): QueryConverter.Result =
        QueryConverter.nhToEh(query) { namespaces[it].orEmpty() }

    fun ehToNh(query: String): QueryConverter.Result = QueryConverter.ehToNh(query)

    /** E-Hentai category bits of a conversion's categories (0 = all). */
    fun categoryBits(result: QueryConverter.Result): Int = result.categories.sumOf { it.bit }

    // MARK: Gallery pages

    /** A gallery's tag namespaces in page order (Swift dictionaries have none). */
    fun tagNamespaces(detail: com.atlanyuey.aenht.shared.eh.EhGalleryDetail): List<String> = detail.tags.keys.toList()

    fun tags(detail: com.atlanyuey.aenht.shared.eh.EhGalleryDetail, namespace: String): List<com.atlanyuey.aenht.shared.eh.EhTag> =
        detail.tags[namespace].orEmpty()

    // MARK: Filters

    fun filterRules(json: String?): List<com.atlanyuey.aenht.shared.site.FilterRule> = com.atlanyuey.aenht.shared.site.ContentFilter.decode(json)
    fun encodeFilterRules(rules: List<com.atlanyuey.aenht.shared.site.FilterRule>): String = com.atlanyuey.aenht.shared.site.ContentFilter.encode(rules)
    fun contentFilter(rules: List<com.atlanyuey.aenht.shared.site.FilterRule>, nhBlacklist: List<Int>): com.atlanyuey.aenht.shared.site.ContentFilter =
        com.atlanyuey.aenht.shared.site.ContentFilter(rules, nhBlacklist.toSet())
    /** A card with what the filter looks at. */
    fun filterCard(site: Site, title: String, uploader: String?, tagIds: List<Int>): GallerySummary = GallerySummary(
        key = GalleryKey(site, 0, null), title = title, thumbnail = null, thumbWidth = 0, thumbHeight = 0,
        pages = 0, languageBadge = null, uploader = uploader, nhTagIds = tagIds,
    )

    fun filterRule(id: String, target: String, pattern: String, regex: Boolean, enabled: Boolean) =
        com.atlanyuey.aenht.shared.site.FilterRule(id, target, pattern, regex, enabled)

    // MARK: Cards

    fun favoriteCard(record: FavoriteRecord): GallerySummary = GallerySummary.from(record)
    fun historyCard(record: HistoryRecord): GallerySummary = GallerySummary.from(record)

    fun summary(
        site: Site, id: Long, token: String?, title: String, thumbnail: String?, thumbWidth: Int, thumbHeight: Int,
        pages: Int, mediaId: String, tagIds: List<Int>,
    ): GallerySummary = GallerySummary(
        key = GalleryKey(site, id, token), title = title, thumbnail = thumbnail, thumbWidth = thumbWidth,
        thumbHeight = thumbHeight, pages = pages, languageBadge = null, nhTagIds = tagIds, mediaId = mediaId,
    )
}
