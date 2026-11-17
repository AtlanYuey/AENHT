package com.atlanyuey.aenht.shared.nh

import com.atlanyuey.aenht.shared.net.RateLimit
import com.atlanyuey.aenht.shared.net.RateLimiter
import com.atlanyuey.aenht.shared.net.arr
import com.atlanyuey.aenht.shared.net.bool
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.net.int
import com.atlanyuey.aenht.shared.net.lenientJson
import com.atlanyuey.aenht.shared.net.obj
import com.atlanyuey.aenht.shared.net.str
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Why an nhentai request failed; the apps turn it into a localized message.
 * [kind] and [code] make the cause readable from Swift as well.
 */
sealed class NhException(message: String, val kind: String, val code: Int = 0, val serverMessage: String? = null) :
    Exception(message) {
    /** No connection, timeout, TLS… */
    class Network(message: String) : NhException(message, "network")

    /** A non-2xx answer; [serverMessage] is the API's own explanation, if any. */
    class Http(code: Int, serverMessage: String?) : NhException(serverMessage ?: "HTTP $code", "http", code, serverMessage)

    /** The server answered something that isn't the expected JSON. */
    class InvalidResponse : NhException("Unexpected response from the server", "invalidResponse")

    /** The action needs an account. */
    class NotLoggedIn : NhException("Please log in first", "notLoggedIn")

    /**
     * The saved login stopped working and could not be renewed. The account
     * is kept (not logged out); the user is asked to verify it again.
     */
    class NeedsVerification : NhException("The login needs to be verified again", "needsVerification")

    /**
     * The login changed while this request waited (another account, or
     * logged out): its answer and its renewal belong to the login before.
     */
    class LoginChanged : NhException("The login changed", "loginChanged")

    /** The new login couldn't be saved on the device; the one there was stays. */
    class NotSaved : NhException("The login couldn't be saved on this device", "notSaved")
}

/** Where the app keeps the API tokens (Keychain on iOS, encrypted storage on Android). */
/** Marks an API key kept in [TokenStorage.accessToken]. */
const val API_KEY_PREFIX = "key:"

/** Atomic credentials plus an in-process identity that changes on explicit login/logout. */
data class TokenSnapshot(val accessToken: String?, val refreshToken: String?, val revision: Long)

interface TokenStorage {
    val accessToken: String?
    val refreshToken: String?
    /** Read all three under the same lock used by credential writes. */
    fun snapshot(): TokenSnapshot
    /** Renew only this exact snapshot; a renewal keeps its session revision. */
    fun renew(expected: TokenSnapshot, access: String, refresh: String?): Boolean
    /** Explicit replacement must also match the revision, including A → B → A. */
    fun replace(expected: TokenSnapshot, access: String, refresh: String?): Boolean
    fun clear()
}

/**
 * nhentai REST API v2 (https://nhentai.net/api/v2/docs). Account actions
 * send the user's token (`Authorization: User <token>`).
 *
 * Every request waits for the official per-endpoint rate limit (see
 * [Limits]), so the app itself never triggers a 429.
 */
class NhApi internal constructor(
    private val tokens: TokenStorage,
    private val http: HttpClient,
    val limiter: RateLimiter = RateLimiter(),
) {
    constructor(tokens: TokenStorage) : this(tokens, buildHttpClient())

    /** Official limits from the API documentation (logged-in values where they differ). */
    internal object Limits {
        val galleries = RateLimit(30)
        val galleriesAnon = RateLimit(15)
        val search = RateLimit(20)
        val searchAnon = RateLimit(10)
        val popular = RateLimit(8)
        val gallery = RateLimit(45)
        val galleryAnon = RateLimit(20)
        val comments = RateLimit(60)
        val commentsAnon = RateLimit(30)
        val postComment = RateLimit(5, 15.minutes)
        val favorites = RateLimit(15)
        val favorite = RateLimit(15)
        val tagSearch = RateLimit(30)
        val user = RateLimit(30)
        val pow = RateLimit(10)
        val blacklist = RateLimit(45)
        val refresh = RateLimit(10)
    }

    val isLoggedIn: Boolean get() = !tokens.accessToken.isNullOrEmpty()

    /** Logged in with an API key (nhentai.net › Settings › API Keys) instead of a login. */
    val usesApiKey: Boolean get() = tokens.accessToken?.startsWith(API_KEY_PREFIX) == true

    /**
     * Logs in with an API key (`Authorization: Key …`): kept like a login
     * token, it never expires and needs no renewal. Answers the account.
     *
     * The key is checked on its own, without touching the saved login; only
     * a working key replaces it, and only if that login is still the one
     * there was when the check started (a logout or another login meanwhile
     * wins: [NhException.LoginChanged]).
     */
    @Throws(NhException::class, CancellationException::class)
    suspend fun loginWithApiKey(key: String): CurrentUser {
        val expected = tokens.snapshot()
        val candidate = API_KEY_PREFIX + key.trim()
        val user = CurrentUser.parse(send("user", "user", Limits.user, candidate = candidate).objOrThrow())
        if (!tokens.replace(expected, candidate, null)) {
            throw if (tokens.snapshot().revision != expected.revision) NhException.LoginChanged() else NhException.NotSaved()
        }
        return user
    }

    /** Called when the login can no longer be renewed (the account is kept). */
    var onNeedsVerification: (() -> Unit)? = null

    private val refreshMutex = Mutex()

    private suspend fun send(
        path: String,
        bucket: String,
        limit: RateLimit,
        method: HttpMethod = HttpMethod.Get,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
        replayAfterRefresh: Boolean = method == HttpMethod.Get,
        retried: Boolean = false,
        /** A login being checked, sent instead of the saved one (never renewed, never saved here). */
        candidate: String? = null,
        expectedRevision: Long? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        limiter.acquire(bucket, limit)
        val login = tokens.snapshot()
        if (candidate == null && expectedRevision != null && login.revision != expectedRevision) throw NhException.LoginChanged()
        val token = candidate ?: login.accessToken?.takeIf { it.isNotEmpty() }
        val (code, text, retryAfter) = try {
            val response = http.request("${NHHost.SITE}/api/v2/$path") {
                this.method = method
                query.forEach { (k, v) -> parameter(k, v) }
                header(HttpHeaders.Accept, "application/json")
                token?.let {
                    header(HttpHeaders.Authorization, if (it.startsWith(API_KEY_PREFIX)) "Key " + it.removePrefix(API_KEY_PREFIX) else "User $it")
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                } else if (method != HttpMethod.Get && method != HttpMethod.Delete) {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            }
            Triple(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.atlanyuey.aenht.shared.app.Diagnostics.log("nhentai", "${method.value} $path: ${e.message ?: e::class.simpleName}")
            throw NhException.Network(com.atlanyuey.aenht.shared.net.NetFailure.describe(e))
        }
        if (code !in 200..299) com.atlanyuey.aenht.shared.app.Diagnostics.log("nhentai", "${method.value} $path → $code ${message(text) ?: ""}")

        if (code == 429 && !retried) {
            // Someone else (another app, the browser) used the quota: wait and try once more.
            val wait = retryAfter?.toLongOrNull()?.coerceIn(1, 120) ?: 30
            limiter.backOff(bucket, limit, wait.seconds)
            return@withContext send(path, bucket, limit, method, query, body, replayAfterRefresh, retried = true, candidate = candidate, expectedRevision = if (candidate == null) login.revision else null)
        }
        // A key being checked: a rejection is the answer, not an expired login.
        if (code == 401 && candidate != null) throw NhException.Http(401, message(text) ?: "Invalid API key")
        if (candidate == null && token != null && tokens.snapshot().revision != login.revision) throw NhException.LoginChanged()
        if (code == 401 && token != null && token.startsWith(API_KEY_PREFIX)) {
            // An API key isn't renewed: it was revoked (or is wrong).
            onNeedsVerification?.invoke()
            throw NhException.NeedsVerification()
        }
        if (code == 401 && token != null && !retried) {
            if (refresh(login, path)) {
                // A rejected request did nothing on the server, so reads are sent
                // again; actions that need a fresh challenge are restarted by their caller.
                if (replayAfterRefresh) {
                    return@withContext send(path, bucket, limit, method, query, body, replayAfterRefresh, retried = true, expectedRevision = login.revision)
                }
                throw AuthRenewed()
            }
            onNeedsVerification?.invoke()
            throw NhException.NeedsVerification()
        }
        if (code !in 200..299) throw NhException.Http(code, message(text))
        try {
            lenientJson.parseToJsonElement(text)
        } catch (_: Exception) {
            throw NhException.InvalidResponse()
        }
    }

    /** The token was renewed; the caller restarts its action once. */
    private class AuthRenewed : Exception()

    private fun message(text: String): String? = try {
        val o = lenientJson.parseToJsonElement(text).obj()
        listOf("error", "detail", "message").firstNotNullOfOrNull { o?.str(it) }
    } catch (_: Exception) {
        null
    }

    private fun JsonElement.objOrThrow(): JsonObject = obj() ?: throw NhException.InvalidResponse()

    // MARK: Raw JSON (the iOS app decodes these with its own models)

    @Throws(NhException::class, CancellationException::class)
    suspend fun galleriesJson(query: String, sort: String, page: Int): String = galleriesElement(query, sort, page).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun popularJson(): String = send("galleries/popular", "popular", Limits.popular).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun galleryJsonText(id: Int): String = galleryJson(id).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun commentsJson(id: Int, page: Int): String = commentsElement(id, page).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun favoritesJson(page: Int, query: String): String = favoritesElement(page, query).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun setFavoriteJson(id: Int, favorite: Boolean): String = setFavoriteElement(id, favorite).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun currentUserJson(): String = send("user", "user", Limits.user).toString()

    @Throws(NhException::class, CancellationException::class)
    suspend fun postCommentJson(id: Int, body: String): String {
        if (!isLoggedIn) throw NhException.NotLoggedIn()
        return try {
            postCommentElement(id, body)
        } catch (_: AuthRenewed) {
            postCommentElement(id, body)
        }.toString()
    }

    @Throws(NhException::class, CancellationException::class)
    suspend fun suggestTagsJson(text: String): String = suggestTagsElement(text).toString()

    // MARK: Galleries

    /** Latest galleries, or a search when a query or a non-date sort is given ("*" matches everything). */
    @Throws(NhException::class, CancellationException::class)
    suspend fun galleries(query: String, sort: String, page: Int): PageResponse<GalleryItem> =
        PageResponse.parse(galleriesElement(query, sort, page).objOrThrow(), GalleryItem::parse)

    private suspend fun galleriesElement(query: String, sort: String, page: Int): JsonElement {
        val q = query.trim()
        return if (q.isEmpty() && sort == "date") {
            send(
                "galleries", "galleries", if (isLoggedIn) Limits.galleries else Limits.galleriesAnon,
                query = listOf("page" to "$page"),
            )
        } else {
            send(
                "search", "search", if (isLoggedIn) Limits.search else Limits.searchAnon,
                query = listOf("query" to q.ifEmpty { "*" }, "sort" to sort, "page" to "$page"),
            )
        }
    }

    @Throws(NhException::class, CancellationException::class)
    suspend fun popular(): List<GalleryItem> =
        send("galleries/popular", "popular", Limits.popular).arr()
            ?.mapNotNull { it.obj()?.let(GalleryItem::parse) }
            ?.filter { !it.blacklisted } ?: emptyList()

    @Throws(NhException::class, CancellationException::class)
    suspend fun gallery(id: Int): GalleryDetail = GalleryDetail.parse(galleryJson(id))

    /** A gallery as the API sends it (downloads keep it for offline use). */
    @Throws(NhException::class, CancellationException::class)
    suspend fun galleryJson(id: Int): JsonObject {
        val include = if (isLoggedIn) "related,favorite" else "related"
        return send(
            "galleries/$id", "gallery", if (isLoggedIn) Limits.gallery else Limits.galleryAnon,
            query = listOf("include" to include),
        ).objOrThrow()
    }

    @Throws(NhException::class, CancellationException::class)
    suspend fun comments(id: Int, page: Int = 1): PageResponse<GalleryComment> =
        PageResponse.parse(commentsElement(id, page).objOrThrow(), GalleryComment::parse)

    private suspend fun commentsElement(id: Int, page: Int): JsonElement = send(
        "galleries/$id/comments", "comments", if (isLoggedIn) Limits.comments else Limits.commentsAnon,
        query = listOf("page" to "${maxOf(1, page)}", "per_page" to "50"),
    )

    // MARK: Account

    @Throws(NhException::class, CancellationException::class)
    suspend fun favorites(page: Int, query: String = ""): PageResponse<GalleryItem> {
        val o = favoritesElement(page, query).objOrThrow()
        // Without a list this isn't the favorites (the copy on the device must not be emptied by it).
        if (o["result"].arr() == null) throw NhException.InvalidResponse()
        return PageResponse.parse(o, GalleryItem::parse)
    }

    private suspend fun favoritesElement(page: Int, query: String): JsonElement {
        if (!isLoggedIn) throw NhException.NotLoggedIn()
        val params = buildList {
            add("page" to "$page")
            if (query.isNotBlank()) add("q" to query.trim())
        }
        return send("favorites", "favorites", Limits.favorites, query = params)
    }

    /** Favoriting is idempotent (it sets a state), so it is sent again after a renewed login. */
    @Throws(NhException::class, CancellationException::class)
    suspend fun setFavorite(id: Int, favorite: Boolean): FavoriteResult {
        val o = setFavoriteElement(id, favorite).obj()
        return FavoriteResult(o?.bool("favorited"), o?.int("num_favorites"))
    }

    private suspend fun setFavoriteElement(id: Int, favorite: Boolean): JsonElement {
        if (!isLoggedIn) throw NhException.NotLoggedIn()
        return send(
            "galleries/$id/favorite", "favorite", Limits.favorite,
            method = if (favorite) HttpMethod.Post else HttpMethod.Delete,
            replayAfterRefresh = true,
        )
    }

    @Throws(NhException::class, CancellationException::class)
    suspend fun currentUser(): CurrentUser = CurrentUser.parse(send("user", "user", Limits.user).objOrThrow())

    /** Posts a comment after solving the API's proof of work (a new one if the login was renewed). */
    @Throws(NhException::class, CancellationException::class)
    suspend fun postComment(id: Int, body: String): GalleryComment =
        GalleryComment.parse(lenientJson.parseToJsonElement(postCommentJson(id, body)).objOrThrow())

    private suspend fun postCommentElement(id: Int, body: String): JsonElement {
        val pow = send("pow", "pow", Limits.pow, query = listOf("action" to "comment")).objOrThrow()
        val challenge = pow.str("challenge") ?: ""
        val difficulty = pow.int("difficulty") ?: 0
        val nonce = withContext(Dispatchers.Default) { ProofOfWork.solve(challenge, difficulty) }
        val payload = buildJsonObject {
            put("body", body)
            put("pow_challenge", challenge)
            put("pow_nonce", nonce)
        }
        return send(
            "galleries/$id/comments", "postComment", Limits.postComment,
            method = HttpMethod.Post, body = payload, replayAfterRefresh = false,
        )
    }

    /** The account's tag blacklist (tag ids, the same ids as list items' `tag_ids`). */
    @Throws(NhException::class, CancellationException::class)
    suspend fun blacklistIds(): List<Int> {
        if (!isLoggedIn) throw NhException.NotLoggedIn()
        return send("blacklist/ids", "blacklist", Limits.blacklist).arr()
            ?.mapNotNull { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: e.obj()?.int("id") }
            ?: emptyList()
    }

    /** A random gallery's id. */
    @Throws(NhException::class, CancellationException::class)
    suspend fun randomGalleryId(): Int =
        send("galleries/random", "random", Limits.galleryAnon).obj()?.let { it.int("id") ?: it["result"].obj()?.int("id") }
            ?: throw NhException.InvalidResponse()

    // MARK: Tags

    @Throws(NhException::class, CancellationException::class)
    suspend fun suggestTags(text: String): List<TagSuggestion> =
        suggestTagsElement(text).arr()?.mapNotNull { it.obj()?.let(TagSuggestion::parse) } ?: emptyList()

    private suspend fun suggestTagsElement(text: String): JsonElement {
        val payload = buildJsonObject {
            put("query", text)
            put("limit", 12)
        }
        return send("tags/search", "tagSearch", Limits.tagSearch, method = HttpMethod.Post, body = payload)
    }

    // MARK: Auth

    /** The last renewal: the token that was rejected and the one that replaced it. */
    private var lastRenewal: Triple<Long, String, String>? = null

    /**
     * Renews the access token once, shared by concurrent requests. [failedToken]
     * is the token that was rejected: if another request already renewed it,
     * this returns true without asking the server again.
     *
     * A request of a login that was replaced meanwhile (another account, a
     * logout) is neither renewed nor sent again with the new login:
     * [NhException.LoginChanged].
     */
    private suspend fun refresh(failed: TokenSnapshot, path: String): Boolean = refreshMutex.withLock {
        val failedToken = failed.accessToken ?: return@withLock false
        val expected = tokens.snapshot()
        if (expected.revision != failed.revision) throw NhException.LoginChanged()
        val current = expected.accessToken
        if (current != failedToken) {
            if (current != null && lastRenewal == Triple(failed.revision, failedToken, current)) return@withLock true
            com.atlanyuey.aenht.shared.app.Diagnostics.log("nhentai", "$path: the login changed while it waited")
            throw NhException.LoginChanged()
        }
        val refreshToken = expected.refreshToken ?: return@withLock false
        val (access, refresh) = try {
            refreshTokens(refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
        }
        // Saved only for the login it renews (a switch or logout meanwhile wins).
        if (!tokens.renew(expected, access, refresh ?: refreshToken)) {
            if (tokens.snapshot().revision != failed.revision) throw NhException.LoginChanged()
            com.atlanyuey.aenht.shared.app.Diagnostics.log("nhentai", "The renewed login couldn't be saved")
            return@withLock false
        }
        lastRenewal = Triple(failed.revision, failedToken, access)
        true
    }

    /** Exchanges a refresh token (no Authorization header). */
    private suspend fun refreshTokens(refreshToken: String): Pair<String, String?> {
        limiter.acquire("refresh", Limits.refresh)
        val response = http.request("${NHHost.SITE}/api/v2/auth/refresh") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("refresh_token", refreshToken) }.toString())
        }
        if (response.status.value != 200) throw NhException.InvalidResponse()
        val o = lenientJson.parseToJsonElement(response.bodyAsText()).objOrThrow()
        val access = o.str("access_token") ?: throw NhException.InvalidResponse()
        return access to o.str("refresh_token")
    }
}
