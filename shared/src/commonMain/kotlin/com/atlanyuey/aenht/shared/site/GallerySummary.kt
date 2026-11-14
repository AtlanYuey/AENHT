package com.atlanyuey.aenht.shared.site

import com.atlanyuey.aenht.shared.db.FavoriteRecord
import com.atlanyuey.aenht.shared.db.HistoryRecord
import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.eh.EhGallerySummary
import com.atlanyuey.aenht.shared.nh.GalleryItem
import com.atlanyuey.aenht.shared.nh.GalleryLanguage
import com.atlanyuey.aenht.shared.nh.NHHost

/**
 * A gallery card of either site: what lists that mix both sites (favorites
 * "All", history, downloads) show. Site-only lists may use the site models.
 */
data class GallerySummary(
    val key: GalleryKey,
    val title: String,
    /** Absolute URL of the cover thumbnail. */
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val pages: Int,
    /** "JP", "ZH", "EN"…, shown in the cover's top-left corner. */
    val languageBadge: String?,
    val category: EhCategory? = null,
    /** 0–5; null when the site has no ratings (nhentai). */
    val rating: Double? = null,
    val uploader: String? = null,
    val posted: String? = null,
    /** Tags to show on cards (names without namespaces). */
    val cardTags: List<String> = emptyList(),
    /** nhentai tag ids (for the tag store); empty for E-Hentai. */
    val nhTagIds: List<Int> = emptyList(),
    val mediaId: String = "",
    val favoriteFolder: Int? = null,
) {
    val site: Site get() = key.site

    /** Width / height of the cover. */
    val aspectRatio: Float
        get() = if (thumbWidth > 0 && thumbHeight > 0) thumbWidth.toFloat() / thumbHeight else 0.7f

    companion object {
        private val ehLanguageBadges = mapOf(
            "japanese" to "JP", "chinese" to "ZH", "english" to "EN", "korean" to "KO", "spanish" to "ES",
            "french" to "FR", "german" to "DE", "italian" to "IT", "portuguese" to "PT", "russian" to "RU",
            "vietnamese" to "VI", "thai" to "TH", "indonesian" to "ID", "polish" to "PL",
        )

        fun ehLanguageBadge(language: String?): String? =
            language?.lowercase()?.let { ehLanguageBadges[it] ?: it.take(2).uppercase() }

        fun from(item: GalleryItem) = GallerySummary(
            key = item.key,
            title = item.title,
            thumbnail = item.thumbUrl,
            thumbWidth = item.thumbnailWidth,
            thumbHeight = item.thumbnailHeight,
            pages = item.numPages,
            languageBadge = item.language?.badge,
            nhTagIds = item.tagIds,
            mediaId = item.mediaId,
        )

        fun from(g: EhGallerySummary) = GallerySummary(
            key = g.key,
            title = g.title,
            thumbnail = g.thumbnail,
            thumbWidth = g.thumbWidth,
            thumbHeight = g.thumbHeight,
            pages = g.pages,
            // E-Hentai galleries without a language tag are Japanese.
            languageBadge = ehLanguageBadge(g.language ?: if (g.category == EhCategory.WESTERN) null else "japanese"),
            category = g.category,
            rating = g.rating,
            uploader = g.uploader,
            posted = g.posted,
            cardTags = g.tags.filter { it.namespace != "language" }.map { it.name },
            favoriteFolder = g.favoriteFolder,
        )

        fun from(r: HistoryRecord) = GallerySummary(
            key = r.galleryKey,
            title = r.title,
            thumbnail = if (r.site == Site.NHENTAI.key) NHHost.thumb(r.thumbnail) else r.thumbnail,
            thumbWidth = r.thumbWidth,
            thumbHeight = r.thumbHeight,
            pages = r.numPages,
            languageBadge = if (r.site == Site.NHENTAI.key) GalleryLanguage.from(r.tagIdList)?.badge else null,
            nhTagIds = r.tagIdList,
            mediaId = r.mediaId,
            rating = r.rating.takeIf { it > 0 },
        )

        fun from(r: FavoriteRecord) = GallerySummary(
            key = GalleryKey(Site.fromKey(r.site) ?: Site.NHENTAI, r.galleryId, r.token),
            title = r.title,
            thumbnail = if (r.site == Site.NHENTAI.key) NHHost.thumb(r.thumbnail) else r.thumbnail,
            thumbWidth = r.thumbWidth,
            thumbHeight = r.thumbHeight,
            pages = r.numPages,
            languageBadge = null,
            mediaId = r.mediaId,
            favoriteFolder = r.folder,
            rating = r.rating.takeIf { it > 0 },
        )
    }
}
