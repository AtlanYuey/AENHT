package com.atlanyuey.aenht.shared.eh

import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.serialization.Serializable

/** E-Hentai categories, with the bit used by the `f_cats` search filter (a set bit hides it). */
enum class EhCategory(val label: String, val bit: Int, val color: Long) {
    DOUJINSHI("Doujinshi", 2, 0xFF9E2720),
    MANGA("Manga", 4, 0xFFDB6C24),
    ARTIST_CG("Artist CG", 8, 0xFFD38F1D),
    GAME_CG("Game CG", 16, 0xFF617C63),
    WESTERN("Western", 512, 0xFFAB9F60),
    NON_H("Non-H", 256, 0xFF5FA9CF),
    IMAGE_SET("Image Set", 32, 0xFF325CA2),
    COSPLAY("Cosplay", 64, 0xFF6A32A2),
    ASIAN_PORN("Asian Porn", 128, 0xFFA23282),
    MISC("Misc", 1, 0xFF777777);

    companion object {
        fun fromLabel(label: String): EhCategory? {
            val l = label.trim().lowercase()
            return entries.firstOrNull { it.label.lowercase() == l }
                ?: when (l) {
                    "private" -> MISC
                    else -> null
                }
        }

        /** `f_cats` value that shows only [shown] (all when empty). */
        fun filterValue(shown: Set<EhCategory>): Int =
            if (shown.isEmpty()) 0 else entries.filter { it !in shown }.sumOf { it.bit }
    }
}

/** A tag such as `female:glasses`; [query] is the exact-match search syntax. */
@Serializable
data class EhTag(val namespace: String, val name: String) {
    val full: String get() = if (namespace.isEmpty()) name else "$namespace:$name"
    val query: String
        get() {
            val value = if (name.contains(' ')) "\"$name$\"" else "$name$"
            return if (namespace.isEmpty()) value else "$namespace:$value"
        }

    companion object {
        private val short = mapOf(
            "f" to "female", "m" to "male", "x" to "mixed", "o" to "other", "a" to "artist",
            "g" to "group", "p" to "parody", "c" to "character", "l" to "language", "r" to "reclass",
            "cos" to "cosplayer", "loc" to "location",
        )

        /** Parses `female:glasses` (or the short `f:glasses`). */
        fun parse(full: String): EhTag {
            val i = full.indexOf(':')
            if (i < 0) return EhTag("", full.trim())
            val ns = full.substring(0, i).trim()
            return EhTag(short[ns] ?: ns, full.substring(i + 1).trim())
        }
    }
}

/** An entry of a gallery list. */
@Serializable
data class EhGallerySummary(
    val gid: Long,
    val token: String,
    val title: String,
    val category: EhCategory?,
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    /** "2026-11-13 21:58" as the site shows it (UTC unless the profile says otherwise). */
    val posted: String,
    val pages: Int,
    val uploader: String?,
    /** 0–5 in half steps, as shown on lists. */
    val rating: Double,
    val tags: List<EhTag>,
    /** 0–9 when this gallery is in one of the user's favorite folders. */
    val favoriteFolder: Int? = null,
    val favoriteNote: String? = null,
    val expunged: Boolean = false,
) {
    val key: GalleryKey get() = GalleryKey(Site.EHENTAI, gid, token)
    val language: String? get() = tags.firstOrNull { it.namespace == "language" && it.name != "translated" && it.name != "rewrite" }?.name
}

/**
 * One page of a list. E-Hentai pages with cursors: [nextCursor] / [prevCursor]
 * are the `next` / `prev` parameters (a gallery id), null at either end.
 */
data class EhListPage(
    val galleries: List<EhGallerySummary>,
    val nextCursor: String?,
    val prevCursor: String?,
    /** Lists with page numbers (toplists, popular) instead of cursors. */
    val pageCount: Int? = null,
)

/** A preview tile: [imageUrl] may be a sprite sheet, cut at [offsetX] with [width]×[height]. */
@Serializable
data class EhThumbnail(
    /** 1-based page number in the gallery. */
    val page: Int,
    /** The page's own URL (`/s/<pagetoken>/<gid>-<page>`). */
    val pageUrl: String,
    val imageUrl: String,
    val offsetX: Int,
    val width: Int,
    val height: Int,
) {
    val isSprite: Boolean get() = offsetX != 0 || width > 0
}

@Serializable
data class EhComment(
    val id: Long,
    val author: String,
    /** "13 November 2026, 21:58" as shown. */
    val posted: String,
    /** null for the uploader's comment. */
    val score: Int?,
    val text: String,
    val isUploader: Boolean,
)

@Serializable
data class EhGalleryDetail(
    val gid: Long,
    val token: String,
    val title: String,
    val japaneseTitle: String?,
    val category: EhCategory?,
    val uploader: String?,
    val cover: String?,
    val posted: String,
    val parent: String?,
    val language: String?,
    val fileSize: String?,
    val pages: Int,
    val favoritedCount: Int,
    val rating: Double,
    val ratingCount: Int,
    /** The user's own rating color class (irb/irr/irg) when they rated it. */
    val ratedByUser: Boolean,
    val tags: Map<String, List<EhTag>>,
    val thumbnails: List<EhThumbnail>,
    /** Number of thumbnail pages (`?p=0…`). */
    val thumbnailPageCount: Int,
    val favoriteFolder: Int?,
    val favoriteName: String?,
    val comments: List<EhComment>,
    val torrentCount: Int,
    val apiUid: Long,
    val apiKey: String,
    val newerVersions: List<String> = emptyList(),
) {
    val key: GalleryKey get() = GalleryKey(Site.EHENTAI, gid, token)
}

/** What an image page (`/s/…`) says about its image. */
data class EhImagePage(
    val imageUrl: String,
    val width: Int,
    val height: Int,
    /** Key for "Reload broken image": request the page again with `nl=<key>`. */
    val reloadKey: String?,
    /** Original image (costs GP for large files), when offered. */
    val originalUrl: String?,
    val fileInfo: String?,
    /** True when the server sent the "509 bandwidth exceeded" placeholder. */
    val limitReached: Boolean,
)
