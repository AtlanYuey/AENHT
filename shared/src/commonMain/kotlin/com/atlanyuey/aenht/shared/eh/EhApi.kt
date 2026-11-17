package com.atlanyuey.aenht.shared.eh

import com.fleeksoft.ksoup.Ksoup
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
    /** Saves a login (all three, or nothing changes): false when it couldn't be saved. */
    fun save(memberId: String, passHash: String, igneous: String?): Boolean

    /**
     * The site gave the account [memberId] an ExHentai cookie: saved only
     * while [memberId] is still the saved login (checked and written in one
     * step); false otherwise.
     */
    fun updateIgneous(memberId: String, igneous: String): Boolean

    fun clear()
}

/** E-Hentai login cookies being checked ([EhApi.checkLogin]); nothing is saved. */
data class EhLoginCookies(val memberId: String, val passHash: String, val igneous: String?)

/** What [EhApi.checkLogin] found: whether the cookies log in, and the ExHentai cookie the site gave. */
data class EhLoginCheck(val ok: Boolean, val igneous: String?)

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
    /** A failed connection: the details to the log, a short sentence to the user. */
    private fun networkFailure(e: Exception): EhException.Network {
        com.atlanyuey.aenht.shared.app.Diagnostics.log("e-hentai", e.message ?: e::class.simpleName ?: "network")
        return EhException.Network(com.atlanyuey.aenht.shared.net.NetFailure.describe(e))
    }

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

    /**
     * Cookies for an image on ExHentai's servers (covers and thumbnails on
     * s.exhentai.org need the account, like the pages) or an original image
     * (e-hentai.org/fullimg); null for anything else.
     */
    fun imageCookieHeader(url: String): String? {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val path = url.substringAfter("://").substringAfter('/', "")
        // Original images (/fullimg/…) need the account on E-Hentai too.
        val original = host == "e-hentai.org" && path.startsWith("fullimg")
        if (host != "exhentai.org" && !host.endsWith(".exhentai.org") && !original) return null
        if (!isLoggedIn) return null
        val cookies = linkedMapOf(
            "ipb_member_id" to credentials.memberId!!,
            "ipb_pass_hash" to credentials.passHash!!,
        )
        credentials.igneous?.takeIf { it.isNotEmpty() && it != "mystery" }?.let { cookies["igneous"] = it }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieHeader(url: String, login: EhLoginCookies? = null): String {
        val cookies = linkedMapOf("nw" to "1") // skip the content warning page
        if (login != null) {
            // A login being checked: its own cookies only, none of the saved session's.
            cookies["ipb_member_id"] = login.memberId
            cookies["ipb_pass_hash"] = login.passHash
            if (url.startsWith(EX)) login.igneous?.takeIf { it.isNotEmpty() && it != "mystery" }?.let { cookies["igneous"] = it }
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        if (isLoggedIn) {
            cookies["ipb_member_id"] = credentials.memberId!!
            cookies["ipb_pass_hash"] = credentials.passHash!!
            if (url.startsWith(EX)) credentials.igneous?.takeIf { it.isNotEmpty() && it != "mystery" }?.let { cookies["igneous"] = it }
        }
        sessionCookies.forEach { (k, v) -> if (k !in cookies) cookies[k] = v }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Keeps cookies the site sets; an `igneous` from exhentai.org is saved
     * with the login the request was sent with ([sentAs], its member id),
     * and only while that is still the saved login.
     */
    private fun remember(response: HttpResponse, sentAs: String?) {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (value.isEmpty() || value == "deleted") return@forEach
            when (name) {
                "igneous" -> if (value != "mystery" && sentAs != null && value != credentials.igneous) {
                    credentials.updateIgneous(sentAs, value)
                }
                "ipb_member_id", "ipb_pass_hash" -> {}
                else -> sessionCookies[name] = value
            }
        }
    }

    /** The `igneous` cookie an answer sets (for a login being checked). */
    private fun igneousOf(response: HttpResponse): String? =
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .map { it.substringBefore(';') }
            .firstOrNull { it.substringBefore('=').trim() == "igneous" }
            ?.substringAfter('=', "")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "deleted" && it != "mystery" }

    private suspend fun getPage(
        url: String,
        bucket: String = "page",
        limit: RateLimit = Limits.page,
        login: EhLoginCookies? = null,
        found: ((igneous: String?) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            limiter.acquire(bucket, limit)
            val sentAs = if (login == null && isLoggedIn) credentials.memberId else null
            val response = try {
                http.get(url) { header(HttpHeaders.Cookie, cookieHeader(url, login)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.atlanyuey.aenht.shared.app.Diagnostics.log("e-hentai", "GET ${url.substringBefore('?')}: ${e.message ?: e::class.simpleName}")
                throw EhException.Network(com.atlanyuey.aenht.shared.net.NetFailure.describe(e))
            }
            if (login == null) remember(response, sentAs) else found?.invoke(igneousOf(response))
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                com.atlanyuey.aenht.shared.app.Diagnostics.log("e-hentai", "GET ${url.substringBefore('?')} → ${response.status.value}")
            }
            EhParser.problem(text)?.let { com.atlanyuey.aenht.shared.app.Diagnostics.log("e-hentai", "GET ${url.substringBefore('?')}: ${it.kind} ${it.message}") }
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
        val page = EhParser.parseList(html)
        // A page without galleries is only "no favorites" when it is the favorites
        // page (it has the folder tabs): a login or error page must not empty the copy.
        if (source is EhListSource.Favorites && page.galleries.isEmpty() && !html.contains("class=\"fp")) {
            throw EhException.Parse("favorites")
        }
        return page
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

    // MARK: Archives and tags

    private fun archiverUrl(gid: Long, token: String, key: String) = "$EH/archiver.php?gid=$gid&token=$token&or=$key"

    /** The kinds of archive offered for a gallery and the account's funds. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun archiveInfo(gid: Long, token: String, key: String): EhArchiveInfo {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        return EhParser.parseArchiveInfo(getPage(archiverUrl(gid, token, key)))
    }

    /**
     * Asks for an archive (costs the GP shown) and answers its download link
     * (a zip on an H@H server, valid for a while).
     */
    @Throws(EhException::class, CancellationException::class)
    suspend fun requestArchive(gid: Long, token: String, key: String, option: EhArchiveOption): String {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val url = archiverUrl(gid, token, key)
        val html = withContext(Dispatchers.IO) {
            limiter.acquire("page", Limits.page)
            val sentAs = if (isLoggedIn) credentials.memberId else null
            val response = try {
                http.post(url) {
                    header(HttpHeaders.Cookie, cookieHeader(url))
                    setBody(FormDataContent(Parameters.build {
                        append("dltype", option.type)
                        append("dlcheck", option.label)
                    }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw networkFailure(e)
            }
            remember(response, sentAs)
            if (response.status.value !in 200..299) throw EhException.Http(response.status.value)
            response.bodyAsText()
        }
        val link = EhParser.parseArchiveLink(html)
            ?: throw EhException.Rejected(EhParser.commentError(html) ?: Ksoup.parse(html).text().take(200).ifBlank { "No archive link" })
        return if ("start=" in link) link else link.trimEnd('/') + "/?start=1"
    }

    /**
     * Votes tags of a gallery up or down; tags not on it yet are added
     * ("female:glasses, male:hat"). Answers the site's error, or null.
     */
    @Throws(EhException::class, CancellationException::class)
    suspend fun voteTags(gid: Long, token: String, apiUid: Long, apiKey: String, tags: String, up: Boolean): String? {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val o = api(
            buildJsonObject {
                put("method", "taggallery")
                put("apiuid", apiUid)
                put("apikey", apiKey)
                put("gid", gid)
                put("token", token)
                put("tags", tags)
                put("vote", if (up) 1 else -1)
            },
        )
        return (o["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    // MARK: Torrents, quota, notes

    @Throws(EhException::class, CancellationException::class)
    suspend fun torrents(gid: Long, token: String): List<EhTorrent> =
        EhParser.parseTorrents(getPage("$host/gallerytorrents.php?gid=$gid&t=$token"))

    /** The account's image quota, or null when the page doesn't show it. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun imageLimit(): EhImageLimit? {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        return EhParser.parseImageLimit(getPage("$EH/home.php"))
    }

    /** The note saved with a favorite. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun favoriteNote(gid: Long, token: String): String {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        // A page without the note field must not read as an empty note: moving the favorite would clear it.
        return EhParser.parseFavoriteNote(getPage("$host/gallerypopups.php?gid=$gid&t=$token&act=addfav"))
            ?: throw EhException.Parse("favorite note")
    }

    /** Votes a comment up (+1) or down (-1); voting the same way again takes the vote back. Answers the new score. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun voteComment(gid: Long, token: String, apiUid: Long, apiKey: String, commentId: Long, up: Boolean): Int? {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val o = api(
            buildJsonObject {
                put("method", "votecomment")
                put("apiuid", apiUid)
                put("apikey", apiKey)
                put("gid", gid)
                put("token", token)
                put("comment_id", commentId)
                put("comment_vote", if (up) 1 else -1)
            },
        )
        return (o["comment_score"] as? JsonPrimitive)?.content?.toIntOrNull()
    }

    // MARK: Comments

    /** Posts a comment (at least 10 characters, as the site requires) and returns the gallery's comments. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun postComment(gid: Long, token: String, text: String): List<EhComment> {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val url = galleryUrl(gid, token) + "?hc=1"
        val html = withContext(Dispatchers.IO) {
            limiter.acquire("page", Limits.page)
            val sentAs = if (isLoggedIn) credentials.memberId else null
            val response = try {
                http.post(url) {
                    header(HttpHeaders.Cookie, cookieHeader(url))
                    setBody(FormDataContent(Parameters.build { append("commenttext_new", text) }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw networkFailure(e)
            }
            remember(response, sentAs)
            if (response.status.value !in 200..299) throw EhException.Http(response.status.value)
            response.bodyAsText()
        }
        EhParser.commentError(html)?.let { throw EhException.Rejected(it) }
        return EhParser.parseComments(html)
    }

    // MARK: Favorites

    /** Adds to / moves into [folder] (0–9) with a [note], or removes when [folder] is null. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun setFavorite(gid: Long, token: String, folder: Int?, note: String = "") {
        if (!isLoggedIn) throw EhException.NotLoggedIn()
        val url = "$host/gallerypopups.php?gid=$gid&t=$token&act=addfav"
        withContext(Dispatchers.IO) {
            limiter.acquire("page", Limits.page)
            val sentAs = if (isLoggedIn) credentials.memberId else null
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
                throw networkFailure(e)
            }
            remember(response, sentAs)
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
            throw networkFailure(e)
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

    /** Basic data of one gallery (a pasted link), or null. */
    @Throws(EhException::class, CancellationException::class)
    suspend fun galleryInfo(gid: Long, token: String): EhGallerySummary? = galleryData(listOf(gid to token)).firstOrNull()

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

    /**
     * Checks login cookies with the site, without touching the saved login:
     * the app saves them only when they work. Also asks exhentai.org, which
     * answers an account with access with its `igneous` cookie.
     */
    @Throws(EhException::class, CancellationException::class)
    suspend fun checkLogin(memberId: String, passHash: String, igneous: String?): EhLoginCheck {
        val login = EhLoginCookies(memberId, passHash, igneous)
        val html = getPage("$EH/home.php", login = login)
        if (!(html.contains("Image Limits") || html.contains("homebox"))) return EhLoginCheck(false, null)
        var given: String? = null
        runCatching { getPage("$EX/", login = login, found = { given = it }) }
        return EhLoginCheck(true, given ?: igneous?.takeIf { it.isNotEmpty() })
    }

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
