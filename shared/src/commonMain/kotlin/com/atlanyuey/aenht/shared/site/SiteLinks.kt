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
    private val nh = Regex("""(?:https?://)?(?:www\.)?nhentai\.(?:net|to)/g/(\d{1,9})""", RegexOption.IGNORE_CASE)
    private val eh = Regex("""(?:https?://)?(?:www\.)?(e-hentai|exhentai)\.org/g/(\d+)/([0-9a-f]{10})""", RegexOption.IGNORE_CASE)
    private val ehPage = Regex("""(?:https?://)?(?:www\.)?(e-hentai|exhentai)\.org/s/([0-9a-f]{10})/(\d+)-(\d+)""", RegexOption.IGNORE_CASE)

    /** The first gallery link in [text], or null. */
    fun find(text: String): GalleryLink? {
        val candidates = listOfNotNull(
            nh.find(text)?.let { it.range.first to GalleryLink.Nh(it.groupValues[1].toInt()) },
            eh.find(text)?.let {
                it.range.first to GalleryLink.Eh(it.groupValues[2].toLong(), it.groupValues[3].lowercase(), it.groupValues[1].equals("exhentai", true))
            },
            ehPage.find(text)?.let {
                it.range.first to GalleryLink.EhPage(
                    it.groupValues[3].toLong(), it.groupValues[2].lowercase(), it.groupValues[4].toInt(),
                    it.groupValues[1].equals("exhentai", true),
                )
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
