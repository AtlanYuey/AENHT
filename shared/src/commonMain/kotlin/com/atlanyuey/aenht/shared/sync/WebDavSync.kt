package com.atlanyuey.aenht.shared.sync

import com.atlanyuey.aenht.shared.db.AenhtDatabase
import com.atlanyuey.aenht.shared.db.FavoriteRecord
import com.atlanyuey.aenht.shared.db.HistoryRecord
import com.atlanyuey.aenht.shared.db.Tombstone
import com.atlanyuey.aenht.shared.net.buildHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Clock

class WebDavException(
    message: String,
    /** The server refused the user name or password. */
    val unauthorized: Boolean = false,
    /** The server's file was written by a newer version of the app: this one must not rewrite it. */
    val newerVersion: Boolean = false,
    /** Another device kept uploading while this one synced: nothing was uploaded, try again later. */
    val conflict: Boolean = false,
) : Exception(message)

/** What one sync changed on this device. */
data class WebDavResult(
    /** The other device's settings (JSON) when they are newer: the app applies them. */
    val settings: String?,
    val settingsChangedAt: Long,
    val historyChanged: Int,
    val favoritesAdded: Int,
)

/** The file kept on the server (`AENHT/sync.json`). */
@Serializable
internal data class SyncFile(
    val version: Int = VERSION,
    val device: String = "",
    val updatedAt: Long = 0,
    val settings: JsonObject? = null,
    val settingsChangedAt: Long = 0,
    val history: List<HistoryRecord> = emptyList(),
    val favorites: List<FavoriteRecord> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
) {
    companion object {
        /** The newest file format this version reads and writes. */
        const val VERSION = 1
    }
}

/**
 * Sync through the user's own WebDAV server (Nextcloud, a NAS, Jianguoyun…),
 * besides iCloud: the settings (the newer copy wins), the reading history
 * (merged; the later read of a gallery wins; deletions travel along) and
 * the favorites copy (taken from the server only for a site this device
 * has no copy of yet: the site's account stays the source of favorites).
 */
class WebDavSync internal constructor(private val db: AenhtDatabase, private val http: HttpClient, private val now: () -> Long) {
    constructor(db: AenhtDatabase) : this(db, buildHttpClient(), { Clock.System.now().toEpochMilliseconds() })

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun folder(base: String) = base.trimEnd('/') + "/AENHT/"

    private suspend fun send(
        method: HttpMethod, url: String, user: String, password: String,
        body: String? = null, depth: String? = null, condition: Pair<String, String>? = null,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            try {
                http.request(url) {
                    this.method = method
                    val credentials = Base64.encode("$user:$password".encodeToByteArray())
                    header(HttpHeaders.Authorization, "Basic $credentials")
                    depth?.let { header("Depth", it) }
                    condition?.let { header(it.first, it.second) }
                    body?.let { contentType(ContentType.Application.Json); setBody(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw WebDavException(e.message ?: "Network error")
            }
        }

    private fun check(response: HttpResponse, allowed: Set<Int> = emptySet()) {
        val code = response.status.value
        if (code in 200..299 || code in allowed) return
        com.atlanyuey.aenht.shared.app.Diagnostics.log("webdav", "${response.call.request.method.value} → $code")
        if (code == 401 || code == 403) throw WebDavException("HTTP $code", unauthorized = true)
        throw WebDavException("HTTP $code")
    }

    /** Checks the address and the sign-in (and creates the app's folder). */
    @Throws(WebDavException::class, CancellationException::class)
    suspend fun test(baseUrl: String, user: String, password: String) {
        check(send(HttpMethod("PROPFIND"), baseUrl.trimEnd('/') + "/", user, password, depth = "0"))
        // 405: the folder already exists.
        check(send(HttpMethod("MKCOL"), folder(baseUrl), user, password), allowed = setOf(405))
    }

    /**
     * Downloads the server's copy, merges it into this device, and uploads
     * the merged copy. [settings] is this device's settings as JSON and
     * [settingsChangedAt] when they last changed.
     *
     * The upload only replaces the copy that was downloaded (its ETag): when
     * another device uploaded in between, the server answers 412 and the
     * sync starts over with that newer copy, so neither device's changes
     * are overwritten.
     */
    @Throws(WebDavException::class, CancellationException::class)
    suspend fun sync(baseUrl: String, user: String, password: String, device: String, settings: String, settingsChangedAt: Long): WebDavResult =
        lock.withLock {
            val url = folder(baseUrl) + "sync.json"
            val local = runCatching { json.parseToJsonElement(settings) as? JsonObject }.getOrNull()
            var historyChanged = 0
            var favoritesAdded = 0
            for (attempt in 1..CONFLICT_RETRIES + 1) {
                val response = send(HttpMethod.Get, url, user, password)
                val remote: SyncFile? = when (response.status.value) {
                    404 -> {
                        check(send(HttpMethod("MKCOL"), folder(baseUrl), user, password), allowed = setOf(405))
                        null
                    }
                    in 200..299 -> runCatching { json.decodeFromString(SyncFile.serializer(), response.bodyAsText()) }.getOrNull()
                        ?: throw WebDavException("The file on the server can't be read.")
                    else -> { check(response); null }
                }
                // A newer app's file may hold what this version doesn't know: leave it alone.
                if (remote != null && remote.version > SyncFile.VERSION) {
                    throw WebDavException("The file on the server was written by a newer version of AENHT.", newerVersion = true)
                }
                val merged = merge(remote, settingsChangedAt)
                historyChanged += merged.historyChanged
                favoritesAdded += merged.favoritesAdded
                val takeRemoteSettings = remote?.settings != null && remote.settingsChangedAt > settingsChangedAt
                val upload = SyncFile(
                    device = device,
                    updatedAt = now(),
                    settings = if (takeRemoteSettings) remote.settings else local,
                    settingsChangedAt = if (takeRemoteSettings) remote.settingsChangedAt else settingsChangedAt,
                    history = db.history().all(),
                    favorites = db.favorites().all(),
                    tombstones = db.tombstones().all(),
                )
                // Weak ETags can't be used with If-Match. A server without strong ETags
                // gets a plain upload: on it, two devices syncing at the same moment
                // aren't protected from each other (the README says so).
                val etag = response.headers[HttpHeaders.ETag]?.takeIf { remote != null && !it.startsWith("W/") }
                val condition = when {
                    remote == null -> HttpHeaders.IfNoneMatch to "*"
                    etag != null -> HttpHeaders.IfMatch to etag
                    else -> null
                }
                val put = send(HttpMethod.Put, url, user, password, body = json.encodeToString(SyncFile.serializer(), upload), condition = condition)
                if (put.status.value == 412 && condition != null) continue
                check(put)
                return@withLock WebDavResult(
                    settings = if (takeRemoteSettings) remote.settings.toString() else null,
                    settingsChangedAt = if (takeRemoteSettings) remote.settingsChangedAt else settingsChangedAt,
                    historyChanged = historyChanged,
                    favoritesAdded = favoritesAdded,
                )
            }
            // Never uploaded without the condition: the other device's copy stays, and the next sync merges it.
            throw WebDavException("The file on the server keeps changing.", conflict = true)
        }

    internal suspend fun merge(remote: SyncFile?, settingsChangedAt: Long): WebDavResult {
        val keepSince = now() - TOMBSTONE_DAYS * 86_400_000L
        db.tombstones().prune(keepSince)
        if (remote == null) return WebDavResult(null, settingsChangedAt, 0, 0)
        // A deletion only ever moves forward in time: an older one from the
        // server must not replace a newer one made here (the gallery read in
        // between would come back).
        val mine = db.tombstones().all().associate { (it.kind to it.key) to it.deletedAt }
        db.tombstones().upsert(remote.tombstones.filter { it.deletedAt >= keepSince && it.deletedAt > (mine[it.kind to it.key] ?: Long.MIN_VALUE) })
        val tombstones = db.tombstones().all()
        val deleted = tombstones.filter { it.kind == Tombstone.HISTORY }.associate { it.key to it.deletedAt }
        val clearedAt = deleted[Tombstone.ALL] ?: 0L
        fun isDeleted(key: String, readAt: Long) = readAt <= clearedAt || (deleted[key] ?: 0L) >= readAt

        var historyChanged = 0
        val history = db.history()
        for (record in history.all()) {
            if (isDeleted(record.key, record.readAt)) { history.delete(record.key); historyChanged++ }
        }
        for (record in remote.history) {
            if (isDeleted(record.key, record.readAt)) continue
            val mine = history.get(record.key)
            if (mine == null || record.readAt > mine.readAt) { history.upsert(record); historyChanged++ }
        }

        val removedFavorites = tombstones.filter { it.kind == Tombstone.FAVORITE }.associate { it.key to it.deletedAt }
        val favorites = db.favorites()
        val sitesHere = favorites.all().map { it.site }.toSet()
        val added = remote.favorites.filter { f ->
            f.site !in sitesHere && (removedFavorites[f.key] ?: 0L) < f.favoritedAt
        }
        if (added.isNotEmpty()) favorites.upsert(added)
        return WebDavResult(null, settingsChangedAt, historyChanged, added.size)
    }

    companion object {
        /** Deletions are remembered this long for devices that sync rarely. */
        const val TOMBSTONE_DAYS = 180

        /** How often a sync starts over when another device uploaded in between. */
        const val CONFLICT_RETRIES = 3
    }
}
