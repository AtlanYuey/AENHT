package com.atlanyuey.aenht.shared.site

/**
 * A gallery site the app reads from. Everything that is stored (history,
 * favorites, downloads, search history) carries the site it belongs to.
 */
enum class Site(val key: String, val badge: String, val displayName: String) {
    NHENTAI("nh", "NH", "nhentai"),
    EHENTAI("eh", "EH", "E-Hentai");

    companion object {
        fun fromKey(key: String): Site? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Identifies one gallery on one site. nhentai needs only the number;
 * E-Hentai needs the gallery id and its token.
 *
 * The string form ([storageKey]) is stable and used as a database key:
 * `nh:123456`, `eh:2345678:abcdef1234`.
 */
data class GalleryKey(val site: Site, val id: Long, val token: String? = null) {
    val storageKey: String
        get() = if (token.isNullOrEmpty()) "${site.key}:$id" else "${site.key}:$id:$token"

    override fun toString(): String = storageKey

    companion object {
        fun nh(id: Int) = GalleryKey(Site.NHENTAI, id.toLong())

        fun parse(storageKey: String): GalleryKey? {
            val parts = storageKey.split(":")
            val site = Site.fromKey(parts.getOrNull(0) ?: return null) ?: return null
            val id = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return GalleryKey(site, id, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
    }
}
