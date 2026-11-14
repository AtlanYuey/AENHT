package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.shared.db.AenhtDatabase
import com.atlanyuey.aenht.shared.db.FavoriteRecord
import com.atlanyuey.aenht.shared.db.openDatabase
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The app's one database (shared module). */
object AppDb {
    @Volatile private var db: AenhtDatabase? = null

    fun get(context: Context): AenhtDatabase =
        db ?: synchronized(this) { db ?: openDatabase(context).also { db = it } }
}

/**
 * A local copy of both sites' account favorites, for Favorites › All (both
 * sites in one list by time) and for offline use.
 *
 * nhentai doesn't say when a gallery was favorited: on the first sync its
 * favorites keep their order and are spread just before the sync time;
 * galleries favorited in the app, or first seen by a later sync, get the
 * time they were seen.
 */
object FavoritesStore {
    private lateinit var db: AenhtDatabase
    val records = mutableStateListOf<FavoriteRecord>()
    var syncing by mutableStateOf<Site?>(null)
        private set
    /** Galleries read by the running sync. */
    var syncedCount by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val lastSync = mutableMapOf<Site, Long>()
    private val lock = Mutex()

    fun init(context: Context) {
        db = AppDb.get(context)
        AppScope.launch { reload() }
    }

    private suspend fun reload() {
        val all = runCatching { db.favorites().observeAllOnce() }.getOrDefault(emptyList())
        records.clear()
        records.addAll(all)
    }

    private suspend fun com.atlanyuey.aenht.shared.db.FavoriteDao.observeAllOnce(): List<FavoriteRecord> =
        bySite(Site.NHENTAI.key).plus(bySite(Site.EHENTAI.key)).sortedByDescending { it.favoritedAt }

    fun cards(site: Site?, ehFolders: Set<Int>?): List<GallerySummary> = records
        .filter { site == null || it.site == site.key }
        .filter { it.site != Site.EHENTAI.key || ehFolders == null || it.folder in ehFolders }
        .map(GallerySummary::from)

    private fun record(card: GallerySummary, folder: Int, time: Long, exact: Boolean) = FavoriteRecord(
        key = card.key.storageKey, site = card.site.key, galleryId = card.key.id, token = card.key.token,
        mediaId = card.mediaId, title = card.title, thumbnail = card.thumbnail,
        thumbWidth = card.thumbWidth, thumbHeight = card.thumbHeight, numPages = card.pages,
        folder = folder, source = "account", favoritedAt = time, exactTime = exact,
        tagIds = card.nhTagIds.joinToString(","),
    )

    /** A gallery was favorited (or moved) in the app: known exactly when. */
    fun added(card: GallerySummary, folder: Int = 0) {
        AppScope.launch {
            lock.withLock {
                val previous = db.favorites().get(card.key.storageKey)
                val rec = record(card, folder, previous?.favoritedAt ?: System.currentTimeMillis(), previous?.exactTime ?: true)
                db.favorites().upsert(listOf(rec))
                reload()
            }
        }
    }

    fun removed(key: GalleryKey) {
        AppScope.launch {
            lock.withLock {
                db.favorites().delete(key.storageKey)
                reload()
            }
        }
    }

    /** Syncs when the last sync of a logged-in site is older than [maxAge]. */
    fun syncIfStale(maxAge: Long = 15 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        val due = AppSettings.enabledSites.filter { loggedIn(it) && now - (lastSync[it] ?: 0) > maxAge }
        if (due.isNotEmpty()) sync(due)
    }

    private fun loggedIn(site: Site) = if (site == Site.NHENTAI) Session.isLoggedIn else EhSession.isLoggedIn

    fun sync(sites: List<Site> = AppSettings.enabledSites) {
        if (syncing != null) return
        AppScope.launch {
            error = null
            for (site in sites.filter(::loggedIn)) {
                syncing = site
                syncedCount = 0
                try {
                    val fetched = fetchAll(site)
                    lock.withLock { merge(site, fetched) }
                    lastSync[site] = System.currentTimeMillis()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
            }
            syncing = null
            reload()
        }
    }

    /** All favorites of a site, newest first, with their folder (E-Hentai). */
    private suspend fun fetchAll(site: Site): List<Pair<GallerySummary, Int>> {
        val out = mutableListOf<Pair<GallerySummary, Int>>()
        if (site == Site.NHENTAI) {
            var page = 1
            while (true) {
                val r = NHClient.favorites(page)
                out += r.result.map { GallerySummary.from(it) to 0 }
                syncedCount = out.size
                if (page >= r.numPages || r.result.isEmpty()) break
                page++
            }
        } else {
            var cursor: String? = null
            while (true) {
                val r = EhClient.list(EhListSource.Favorites(null), cursor)
                out += r.galleries.map { GallerySummary.from(it) to (it.favoriteFolder ?: 0) }
                syncedCount = out.size
                cursor = r.nextCursor ?: break
                if (r.galleries.isEmpty()) break
            }
        }
        return out
    }

    private suspend fun merge(site: Site, fetched: List<Pair<GallerySummary, Int>>) {
        val dao = db.favorites()
        val existing = dao.bySite(site.key).associateBy { it.key }
        val firstSync = existing.isEmpty()
        val now = System.currentTimeMillis()
        val merged = fetched.mapIndexed { index, (card, folder) ->
            val old = existing[card.key.storageKey]
            when {
                old != null -> record(card, folder, old.favoritedAt, old.exactTime)
                // First sync: the site's order, spread a second apart before now.
                firstSync -> record(card, folder, now - index * 1000L, exact = false)
                // Seen for the first time now: favorited since the last sync.
                else -> record(card, folder, now - index, exact = true)
            }
        }
        dao.upsert(merged)
        val kept = merged.map { it.key }.toSet()
        existing.keys.filter { it !in kept }.forEach { dao.delete(it) }
    }
}
