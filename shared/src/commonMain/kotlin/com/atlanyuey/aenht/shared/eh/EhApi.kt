package com.atlanyuey.aenht.shared.eh

import com.atlanyuey.aenht.shared.net.RateLimit
import com.atlanyuey.aenht.shared.net.RateLimiter
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.net.lenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** The E-Hentai login cookies, kept by the app (Keychain / Android Keystore). */
interface EhCredentialStorage {
    val memberId: String?
    val passHash: String?
    /** ExHentai access cookie, given by exhentai.org to accounts that have access. */
    val igneous: String?
    fun save(memberId: String, passHash: String, igneous: String?)
    fun clear()
}

/** Which list to load. */
sealed class EhListSource {
    /** Front page, or a search when [query] / [categories] / [advanced] are set. */
    data class Search(
        val query: String = "",
        val categories: Set<EhCategory> = emptySet(),
        val advanced: EhAdvancedSearch? = null,
    ) : EhListSource()

    data object Popular : EhListSource()

    /** Galleries with the user's watched tags (needs a login). */
    data class Watched(val query: String = "") : EhListSource()

    /** The user's favorites; [folder] 0–9, or null for all folders. */
    data class Favorites(val folder: Int? = null, val query: String = "") : EhListSource()

    /** Toplists (e-hentai.org only), with page numbers. */
    data class Toplist(val period: EhToplistPeriod) : EhListSource()
}

enum class EhToplistPeriod(val tl: Int) { YESTERDAY(15), PAST_MONTH(13), PAST_YEAR(12), ALL_TIME(11) }

/** The "Show Advanced Options" of the search form. */
data class EhAdvancedSearch(
    val showExpunged: Boolean = false,
    val requireTorrent: Boolean = false,
    /** 2–5 stars, or null. */
    val minRating: Int? = null,
    val minPages: Int? = null,
    val maxPages: Int? = null,
    /** Ignore the account's own language / uploader / tag filters for this search. */
    val disableLanguageFilter: Boolean = false,
    val disableUploaderFilter: Boolean = false,
    val disableTagFilter: Boolean = false,
)

/**
 * E-Hentai and ExHentai. Pages are read as HTML (see [EhParser]); ratings
 * and gallery data go through `api.php`.
 *
 * The site bans an IP that loads pages too fast, so every request waits in
 * the shared [limiter] (one per site, used by browsing, downloads and
 * favorites sync alike).
 */
class EhApi internal constructor(
    private val credentials: EhCredentialStorage,
    private val http: HttpClient,
    val limiter: RateLimiter = RateLimiter(),
) {
    constructor(credentials: EhCredentialStorage) : this(credentials, buildHttpClient())

    internal object Limits {
        /** Lists, gallery pages, favorites: about one page a second, in bursts of 5. */
        val page = RateLimit(5, 5.seconds)
        /** Image pages (`/s/…`) while reading or downloading. */
        val image = RateLimit(12, 6.seconds)
        val api = RateLimit(5, 5.seconds)
    }

    /** Use exhentai.org (needs a login with ExHentai access). */
    var useExHentai: Boolean = false

    val isLoggedIn: Boolean get() = !credentials.memberId.isNullOrEmpty() && !credentials.passHash.isNullOrEmpty()

    /** True when ExHentai is selected and usable. */
    val isExHentai: Boolean get() = useExHentai && isLoggedIn

    val host: String get() = if (isExHentai) EX else EH

    /** Cookies the site set during this session (e.g. `sk`), besides the login. */
    private val sessionCookies = mutableMapOf<String, String>()

    private fun cookieHeader(url: String): String {
        val cookies = linkedMapOf("nw" to "1") // skip the content warning page
        if (isLoggedIn) {
            cookies["ipb_member_id"] = credentials.memberId!!
            cookies["ipb_pass_hash"] = credentials.passHash!!
            if (url.startsWith(EX)) credentials.igneous?.takeIf { it.isNotEmpty() && it != "mystery" }?.let { cookies["igneous"] = it }
        }
        sessionCookies.forEach { (k, v) -> cookies.putIfAbsent(k, v) }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Keeps cookies the site sets; an `igneous` from exhentai.org is saved with the login. */
    private fun remember(response: HttpResponse) {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (value.isEmpty() || value == "deleted") return@forEach
            when (name) {
                "igneous" -> if (value != "mystery" && isLoggedIn && value != credentials.igneous) {
                    credentials.save(credentials.memberId!!, credentials.passHash!!, value)
                }
                "ipb_member_id", "ipb_pass_hash" -> {}
                else -> sessionCookies[name] = value
            }
        }
    }

    private suspend fun getPage(url: String, bucket: String = "page", limit: RateLimit = Limits.page): String =
        withContext(Dispatchers.IO) {
            limiter.acquire(bucket, limit)
            val response = try {
                http.get(url) { header(HttpHeaders.Cookie, cookieHeader(url)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw EhException.Network(e.message ?: e.toString())
            }
            remember(response)
            val text = response.bodyAsText()
            when (response.status.value) {
                in 200..299 -> {}
                404 -> throw EhException.NotFound()
                509 -> throw EhException.LimitReached()
                else -> throw EhParser.problem(text) ?: EhException.Http(response.status.value)
            }
            EhParser.problem(text)?.let { throw it }
            text
        }

    // MARK: Lists

    /** The URL of a list page; [cursor] is the `next` value of the previous page (or a page number for toplists). */
    fun listUrl(source: EhListSource, cursor: String? = null): String {
        val url = when (source) {
            is EhListSource.Search -> URLBuilder("$host/").apply {
                if (source.query.isNotBlank()) parameters.append("f_search", source.query.trim())
                val cats = EhCategory.filterValue(source.categories)
                if (cats != 0) parameters.append("f_cats", "$cats")
                source.advanced?.let { a ->
                    parameters.append("advsearch", "1")
                    if (a.showExpunged) parameters.append("f_sh", "on")
                    if (a.requireTorrent) parameters.append("f_sto", "on")
                    a.minRating?.let { parameters.append("f_srdd", "$it") }
                    a.minPages?.let { parameters.append("f_spf", "$it") }
                    a.maxPages?.let { parameters.append("f_spt", "$it") }
                    if (a.disableLanguageFilter) parameters.append("f_sfl", "on")
                    if (a.disableUploaderFilter) parameters.append("f_sfu", "on")
                    if (a.disableTagFilter) parameters.append("f_sft", "on")
                }
            }
            EhListSource.Popular -> URLBuilder("$host/popular")
            is EhListSource.Watched -> URLBuilder("$host/watched").apply {
                if (source.query.isNotBlank()) parameters.append("f_search", source.query.trim())
            }
            is EhListSource.Favorites -> URLBuilder("$host/favorites.php").apply {
                parameters.append("favcat", source.folder?.toString() ?: "all")
                if (source.query.isNotBlank()) parameters.append("f_search", source.query.trim())
            }
            is EhListSource.Toplist -> URLBuilder("$EH/toplist.php").apply {
                parameters.append("tl", "${source.period.tl}")
                cursor?.let { parameters.append("p", it) }
            }
        }
        if (cursor != null && source !is EhListSource.Toplist) url.parameters.append("next", cursor)
        return url.buildString()
    }

    /** One page of a list. Lists in another display mode are switched to Compact once. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun list(source: EhListSource, cursor: String? = null): EhListPage {
        if (source is EhListSource.Watched && !isLoggedIn) throw EhException.NotLoggedIn()
        if (source is EhListSource.Favorites && !isLoggedIn) throw EhException.NotLoggedIn()
        val url = listUrl(source, cursor)
        var html = getPage(url)
        if (EhParser.needsCompactMode(html)) {
            // Switches the account's display mode to Compact (what the app reads).
            getPage(URLBuilder("$host/").apply { parameters.append("inline_set", "dm_l") }.buildString())
            html = getPage(url)
        }
        return EhParser.parseList(html)
    }

    // MARK: Galleries

    fun galleryUrl(gid: Long, token: String) = "$host/g/$gid/$token/"

    /** A gallery with its first thumbnail page and all comments. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun gallery(gid: Long, token: String): EhGalleryDetail =
        EhParser.parseGallery(getPage(galleryUrl(gid, token) + "?hc=1"))

    /** Thumbnail page [page] (0-based) of a gallery. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun thumbnails(gid: Long, token: String, page: Int): List<EhThumbnail> =
        EhParser.parseThumbnails(getPage(galleryUrl(gid, token) + "?p=$page"))

    /**
     * The image of a page. [reloadKey] (from a previous answer) asks the
     * server for the image from another node when the first one failed.
     */
    @Throws(EhException::class, CancellationException::class)
    suspend fun imagePage(pageUrl: String, reloadKey: String? = null): EhImagePage {
        val url = if (reloadKey != null) pageUrl + (if ('?' in pageUrl) "&" else "?") + "nl=" + reloadKey.encodeURLParameter() else pageUrl
        val page = EhParser.parseImagePage(getPage(url.replace(EH, host).replace(EX, host), "image", Limits.image))
        if (page.limitReached) throw EhException.LimitReached()
        return page
    }

    // MARK: Favorites

    /** Adds to / moves into [folder] (0–9) with a [note], or removes when [folder] is null. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun setFavorite(gid: Long, token: String, folder: Int?, note: String = "") {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val url = "$host/gallerypopups.php?gid=$gid&t=$token&act=addfav"
        withContext(Dispatchers.IO) {
            limiter.acquire("page", Limits.page)
            val response = try {
                http.post(url) {
                    header(HttpHeaders.Cookie, cookieHeader(url))
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("favcat", folder?.toString() ?: "favdel")
                                append("favnote", note)
                                append("apply", if (folder == null) "Apply Changes" else "Add to Favorites")
                                append("update", "1")
                            },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw EhException.Network(e.message ?: e.toString())
            }
            remember(response)
            if (response.status.value !in 200..299) throw EhException.Http(response.status.value)
        }
    }

    /** The user's favorite folder names (10 entries; the site's defaults when not renamed). */
    @Throws(EhException::class, CancellationException::class)
    suspend fun favoriteFolderNames(): List<String> {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val html = getPage("$host/favorites.php")
        val names = Regex("""<div class="fp[^"]*"[^>]*>\s*<div>[\d,]+</div>\s*<div class="i"[^>]*></div>\s*<div>([^<]*)</div>""")
            .findAll(html).map { it.groupValues[1].trim() }.toList()
        return if (names.size >= 10) names.take(10) else List(10) { "Favorites $it" }
    }

    // MARK: API (api.php)

    private suspend fun api(body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        limiter.acquire("api", Limits.api)
        val url = if (isExHentai) "$EX/api.php" else "https://api.e-hentai.org/api.php"
        val response = try {
            http.post(url) {
                header(HttpHeaders.Cookie, cookieHeader(url))
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EhException.Network(e.message ?: e.toString())
        }
        if (response.status.value !in 200..299) throw EhException.Http(response.status.value)
        try {
            lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (_: Exception) {
            throw EhException.Parse("api")
        }
    }

    /** Rates a gallery 0.5–5 stars (needs the api key of its page). Returns the new average. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun rate(gid: Long, token: String, apiUid: Long, apiKey: String, stars: Double): Double {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val o = api(
            buildJsonObject {
                put("method", "rategallery")
                put("apiuid", apiUid)
                put("apikey", apiKey)
                put("gid", gid)
                put("token", token)
                put("rating", (stars * 2).toInt().coerceIn(1, 10))
            },
        )
        return (o["rating_avg"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    }

    /** Basic data of up to 25 galleries at once (links, history repairs). */
    @Throws(EhException::class, CancellationException::class)
    suspend fun galleryData(keys: List<Pair<Long, String>>): List<EhGallerySummary> {
        if (keys.isEmpty()) return emptyList()
        val o = api(
            buildJsonObject {
                put("method", "gdata")
                put("namespace", 1)
                put("gidlist", buildJsonArray { keys.take(25).forEach { (gid, token) -> add(buildJsonArray { add(JsonPrimitive(gid)); add(JsonPrimitive(token)) }) } })
            },
        )
        return (o["gmetadata"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            if (m["error"] != null) return@mapNotNull null
            fun s(k: String) = (m[k] as? JsonPrimitive)?.content
            EhGallerySummary(
                gid = s("gid")?.toLongOrNull() ?: return@mapNotNull null,
                token = s("token") ?: return@mapNotNull null,
                title = s("title").orEmpty(),
                category = s("category")?.let(EhCategory::fromLabel),
                thumbnail = s("thumb"),
                thumbWidth = 0,
                thumbHeight = 0,
                posted = s("posted").orEmpty(),
                pages = s("filecount")?.toIntOrNull() ?: 0,
                uploader = s("uploader"),
                rating = s("rating")?.toDoubleOrNull() ?: 0.0,
                tags = (m["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(EhTag::parse) },
                expunged = s("expunged") == "true",
            )
        }
    }

    /** The gallery token for an image page link (`/s/<pagetoken>/<gid>-<page>`). */
    @Throws(EhException::class, CancellationException::class)
    suspend fun galleryToken(gid: Long, pageToken: String, page: Int): String {
        val o = api(
            buildJsonObject {
                put("method", "gtoken")
                put("pagelist", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(gid)); add(JsonPrimitive(pageToken)); add(JsonPrimitive(page)) }) })
            },
        )
        val first = (o["tokenlist"] as? JsonArray)?.firstOrNull() as? JsonObject
        return (first?.get("token") as? JsonPrimitive)?.content ?: throw EhException.NotFound()
    }

    // MARK: Account

    /** Checks the saved cookies by loading a page that needs them; also picks up ExHentai access. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun verifyLogin(): Boolean {
        if (!isLoggedIn) return false
        val html = getPage("$EH/home.php")
        val ok = html.contains("Image Limits") || html.contains("homebox")
        if (ok) runCatching { getPage("$EX/") } // exhentai.org answers with an igneous cookie when allowed
        return ok
    }

    /** Whether the account can use ExHentai (after [verifyLogin]). */
    val hasExHentaiAccess: Boolean get() = !credentials.igneous.isNullOrEmpty() && credentials.igneous != "mystery"

    fun logout() {
        credentials.clear()
        sessionCookies.clear()
    }

    companion object {
        const val EH = "https://e-hentai.org"
        const val EX = "https://exhentai.org"
    }
}
