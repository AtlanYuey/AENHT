package com.atlanyuey.aenht.shared.eh

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses E-Hentai / ExHentai pages. Lists are read in the "Compact" display
 * mode (the client asks for it); detail and image pages have one layout.
 */
object EhParser {
    private val galleryUrl = Regex("""/g/(\d+)/([0-9a-f]+)/?""")
    private val pageUrl = Regex("""/s/([0-9a-f]+)/(\d+)-(\d+)""")
    private val px = Regex("""(-?\d+)px""")
    private val cssUrl = Regex("""url\(([^)]+)\)""")
    private val pagesText = Regex("""(\d+) pages?""")
    private val number = Regex("""\d[\d,]*""")

    /** Favorite folder colors as shown on lists (border-color of the posted date). */
    private val folderColors = listOf("#000", "#f00", "#fa0", "#dd0", "#080", "#9f4", "#4bf", "#00f", "#508", "#e8e")

    // MARK: Problems

    /** A page that isn't what was asked for; null when it's fine. */
    fun problem(html: String): EhException? {
        val t = html.trim()
        return when {
            t.isEmpty() -> EhException.NoAccess()
            t.startsWith("Your IP address has been temporarily banned") || t.contains("temporarily banned for excessive") ->
                EhException.Banned(t.take(300))
            t.startsWith("This gallery has been removed") || t.contains("<title>Gallery Not Available") ->
                EhException.NotFound()
            t.contains("Key missing, or incorrect key provided") -> EhException.NotFound()
            else -> null
        }
    }

    // MARK: Lists

    /** True when the list isn't in Compact mode (the client then switches the mode and asks again). */
    fun needsCompactMode(html: String): Boolean {
        val doc = Ksoup.parse(html)
        val table = doc.selectFirst("table.itg, div.itg") ?: return false
        return !table.hasClass("gltc")
    }

    fun parseList(html: String): EhListPage {
        val doc = Ksoup.parse(html)
        val rows = doc.select("table.itg.gltc > tbody > tr, table.itg.gltc > tr")
        val galleries = rows.mapNotNull { parseRow(it) }
        fun cursor(id: String, param: String): String? =
            doc.getElementById(id)?.attr("href")
                ?.let { Regex("""[?&]$param=([^&#]+)""").find(it)?.groupValues?.get(1) }
        val next = cursor("unext", "next") ?: cursor("dnext", "next")
        val prev = cursor("uprev", "prev") ?: cursor("dprev", "prev")
        // Toplists and some lists use page numbers (table.ptt).
        val pageCount = doc.select("table.ptt td a").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull()
        return EhListPage(galleries, next, prev, pageCount)
    }

    private fun parseRow(row: Element): EhGallerySummary? {
        val link = row.selectFirst("td.glname a[href*=/g/], td.gl3c a[href*=/g/]") ?: return null
        val m = galleryUrl.find(link.attr("href")) ?: return null
        val title = row.selectFirst(".glink")?.text()?.trim().orEmpty()
        val category = row.selectFirst("td.glcat div.cn, td.gl1c div.cn")?.text()?.let(EhCategory::fromLabel)
        val img = row.selectFirst(".glthumb img")
        val thumb = img?.let { it.attr("data-src").ifEmpty { it.attr("src") } }?.takeUnless { it.startsWith("data:") }
        val style = img?.attr("style").orEmpty()
        val height = Regex("""height:(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val width = Regex("""width:(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val posted = row.selectFirst("[id^=posted_]")
        val rating = row.selectFirst("div.ir")?.attr("style")?.let(::ratingFromStyle) ?: 0.0
        val pages = pagesText.find(row.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val uploader = row.selectFirst("a[href*=/uploader/]")?.text()?.trim()
        val tags = row.select("div.gt, div.gtl").mapNotNull { tag ->
            tag.attr("title").takeIf { it.isNotEmpty() }?.let(EhTag::parse)
        }
        val folderColor = posted?.attr("style")?.let { Regex("""border-color:\s*(#\w{3,6})""").find(it)?.groupValues?.get(1) }
        val folder = folderColor?.let { c -> folderColors.indexOf(c.lowercase()).takeIf { it >= 0 } }
        return EhGallerySummary(
            gid = m.groupValues[1].toLong(),
            token = m.groupValues[2],
            title = title,
            category = category,
            thumbnail = thumb,
            thumbWidth = width,
            thumbHeight = height,
            posted = posted?.text()?.trim().orEmpty(),
            pages = pages,
            uploader = uploader,
            rating = rating,
            tags = tags,
            favoriteFolder = folder,
            favoriteNote = row.selectFirst(".glfnote")?.text()?.removePrefix("Note:")?.trim()?.takeIf { it.isNotEmpty() },
            expunged = posted?.selectFirst("s") != null,
        )
    }

    /** The star sprite: x = -16px per missing star, y = -21px for a half star. */
    fun ratingFromStyle(style: String): Double {
        val values = px.findAll(style.substringAfter("background-position")).map { it.groupValues[1].toInt() }.toList()
        if (values.size < 2) return 0.0
        val x = -values[0]
        val y = -values[1]
        return ((80 - x) / 16.0 - if (y == 21) 0.5 else 0.0).coerceIn(0.0, 5.0)
    }

    // MARK: Gallery

    fun parseGallery(html: String): EhGalleryDetail {
        val doc = Ksoup.parse(html)
        val script = doc.select("script").joinToString("\n") { it.data() }
        fun scriptVar(name: String) = Regex("""var $name\s*=\s*"?([^";]+)"?;""").find(script)?.groupValues?.get(1)?.trim()

        val gid = scriptVar("gid")?.toLongOrNull()
            ?: galleryUrl.find(doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            ?: throw EhException.Parse("gallery id")
        val token = scriptVar("token") ?: throw EhException.Parse("gallery token")

        val info = doc.select("#gdd tr").associate { tr ->
            tr.selectFirst(".gdt1")?.text()?.trim()?.removeSuffix(":").orEmpty() to tr.selectFirst(".gdt2")
        }
        fun infoText(name: String) = info[name]?.text()?.replace(' ', ' ')?.trim()?.takeIf { it.isNotEmpty() }

        val coverStyle = doc.selectFirst("#gd1 div")?.attr("style").orEmpty()
        val cover = cssUrl.find(coverStyle)?.groupValues?.get(1)?.trim('\'', '"')

        val tags = linkedMapOf<String, List<EhTag>>()
        doc.select("#taglist tr").forEach { tr ->
            val ns = tr.selectFirst("td.tc")?.text()?.trim()?.removeSuffix(":").orEmpty()
            val list = tr.select("td div.gt, td div.gtl, td div.gtw").map { div ->
                val id = div.id().removePrefix("td_")
                if (id.contains(':')) EhTag.parse(id.replace('_', ' ')) else EhTag(ns, div.text().trim())
            }
            if (list.isNotEmpty()) tags[ns] = list
        }

        val favDiv = doc.selectFirst("#fav > div")
        val favoriteFolder = favDiv?.attr("style")?.let { style ->
            px.findAll(style.substringAfter("background-position", "")).map { it.groupValues[1].toInt() }.toList()
                .getOrNull(1)?.let { y -> ((-y - 2) / 19).takeIf { it in 0..9 } }
        }

        val ratingImage = doc.getElementById("rating_image")
        val ratingCount = doc.getElementById("rating_count")?.text()?.replace(",", "")?.toIntOrNull() ?: 0

        return EhGalleryDetail(
            gid = gid,
            token = token,
            title = doc.getElementById("gn")?.text()?.trim().orEmpty(),
            japaneseTitle = doc.getElementById("gj")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            category = doc.selectFirst("#gdc div")?.text()?.let(EhCategory::fromLabel),
            uploader = doc.selectFirst("#gdn a")?.text()?.trim(),
            cover = cover,
            posted = infoText("Posted").orEmpty(),
            parent = info["Parent"]?.selectFirst("a")?.attr("href"),
            language = infoText("Language"),
            fileSize = infoText("File Size"),
            pages = infoText("Length")?.let { number.find(it)?.value?.replace(",", "")?.toIntOrNull() } ?: 0,
            favoritedCount = doc.getElementById("favcount")?.text()?.let {
                if (it.startsWith("Once")) 1 else number.find(it)?.value?.replace(",", "")?.toIntOrNull()
            } ?: 0,
            rating = scriptVar("average_rating")?.toDoubleOrNull()
                ?: doc.getElementById("rating_label")?.text()?.substringAfter("Average:")?.trim()?.toDoubleOrNull() ?: 0.0,
            ratingCount = ratingCount,
            ratedByUser = ratingImage?.classNames()?.any { it in setOf("irr", "irg", "irb") } == true,
            tags = tags,
            thumbnails = parseThumbnails(doc),
            thumbnailPageCount = doc.select("table.ptt td a").mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: 1,
            favoriteFolder = favoriteFolder,
            favoriteName = favDiv?.attr("title")?.takeIf { it.isNotEmpty() },
            comments = parseComments(doc),
            torrentCount = Regex("""Torrent Download \((\d+)\)""").find(doc.select("#gd5").text())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            apiUid = scriptVar("apiuid")?.toLongOrNull() ?: -1,
            apiKey = scriptVar("apikey").orEmpty(),
            newerVersions = doc.select("#gnd a").map { it.attr("href") },
        )
    }

    /** The preview tiles of one thumbnail page (`?p=N`), small (sprites) or large. */
    fun parseThumbnails(html: String): List<EhThumbnail> = parseThumbnails(Ksoup.parse(html))

    private fun parseThumbnails(doc: Document): List<EhThumbnail> =
        doc.select("#gdt a").mapNotNull { a ->
            val href = a.attr("href")
            val page = pageUrl.find(href)?.groupValues?.get(3)?.toIntOrNull() ?: return@mapNotNull null
            val div = a.selectFirst("div[style]")
            val img = a.selectFirst("img")
            when {
                div != null && div.attr("style").contains("url(") -> {
                    val style = div.attr("style")
                    val url = cssUrl.find(style)?.groupValues?.get(1)?.trim('\'', '"') ?: return@mapNotNull null
                    val w = Regex("""width:\s*(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val h = Regex("""height:\s*(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val offset = Regex("""\)\s*(-?\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    EhThumbnail(page, href, url, -offset, w, h)
                }
                img != null -> EhThumbnail(page, href, img.attr("src"), 0, 0, 0)
                else -> null
            }
        }

    private fun parseComments(doc: Document): List<EhComment> =
        doc.select("#cdiv div.c1").mapNotNull { c ->
            val body = c.selectFirst("div.c6") ?: return@mapNotNull null
            val id = body.id().removePrefix("comment_").toLongOrNull() ?: return@mapNotNull null
            val header = c.selectFirst("div.c3")
            val headerText = header?.text().orEmpty()
            val score = c.selectFirst("div.c5 span")?.text()?.trim()?.toIntOrNull()
            EhComment(
                id = id,
                author = header?.selectFirst("a")?.text()?.trim().orEmpty(),
                posted = headerText.substringAfter("Posted on ", "").substringBefore(" by:").trim(),
                score = score,
                text = textWithBreaks(body),
                isUploader = id == 0L,
            )
        }

    /** Element text with `<br>` as line breaks. */
    private fun textWithBreaks(element: Element): String {
        val marker = "⁣BR⁣"
        element.select("br").forEach { it.after(com.fleeksoft.ksoup.nodes.TextNode(marker)) }
        return element.text().split(marker).joinToString("\n") { it.trim() }.trim()
    }

    // MARK: Image page

    fun parseImagePage(html: String): EhImagePage {
        val doc = Ksoup.parse(html)
        val img = doc.getElementById("img") ?: throw EhException.Parse("image")
        val src = img.attr("src")
        val style = img.attr("style")
        val info = doc.selectFirst("#i2 > div:not(.sn)")?.text()?.trim()
            ?: doc.selectFirst("#i4 > div")?.text()?.trim()
        val dims = info?.let { Regex("""(\d+) x (\d+)""").find(it) }
        val reloadKey = (doc.getElementById("loadfail")?.attr("onclick") ?: img.attr("onerror"))
            .let { Regex("""nl\('([^']+)'\)""").find(it)?.groupValues?.get(1) }
        return EhImagePage(
            imageUrl = src,
            width = dims?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""width:(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            height = dims?.groupValues?.get(2)?.toIntOrNull()
                ?: Regex("""height:(\d+)px""").find(style)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            reloadKey = reloadKey,
            originalUrl = doc.selectFirst("a[href*=/fullimg]")?.attr("href"),
            fileInfo = info,
            limitReached = src.endsWith("/509.gif") || src.contains("/509s.gif"),
        )
    }

    // MARK: Links

    /** The gallery a link points to (`/g/<gid>/<token>/`), or null. */
    fun galleryKey(url: String): Pair<Long, String>? =
        galleryUrl.find(url)?.let { it.groupValues[1].toLong() to it.groupValues[2] }

    /** An image page link (`/s/<pagetoken>/<gid>-<page>`) as (pagetoken, gid, page). */
    fun imagePageKey(url: String): Triple<String, Long, Int>? =
        pageUrl.find(url)?.let { Triple(it.groupValues[1], it.groupValues[2].toLong(), it.groupValues[3].toInt()) }
}

/** Why an E-Hentai request failed. [kind] is readable from Swift. */
sealed class EhException(message: String, val kind: String, val code: Int = 0) : Exception(message) {
    class Network(message: String) : EhException(message, "network")
    class Http(code: Int) : EhException("HTTP $code", "http", code)
    /** The IP is banned for a while (too many requests); [message] says for how long. */
    class Banned(message: String) : EhException(message, "banned")
    /** ExHentai answered with an empty page: the account has no access ("sad panda"). */
    class NoAccess : EhException("No access to ExHentai with this account", "noAccess")
    class NotFound : EhException("The gallery is not available", "notFound")
    class NotLoggedIn : EhException("Please log in first", "notLoggedIn")
    /** The image quota is used up (509). */
    class LimitReached : EhException("The image limit has been reached", "limitReached")
    class Parse(what: String) : EhException("Unexpected page from the server ($what)", "parse")
}
