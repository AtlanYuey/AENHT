package com.atlanyuey.aenht.shared.db

import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.nh.NhApi
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** Reports how many favorites a sync has read so far. */
fun interface SyncProgress {
    fun update(count: Int)
}

/**
 * A local copy of both sites' account favorites (Favorites › All: both
 * sites in one list by time, and offline).
 *
 * nhentai doesn't say when a gallery was favorited: on the first sync its
 * favorites keep their order and are spread just before the sync time;
 * galleries favorited in the app, or first seen by a later sync, get the
 * time they were seen.
 */
class FavoritesMirror internal constructor(
    private val db: AenhtDatabase,
    private val nh: NhApi,
    private val eh: EhApi,
    private val now: () -> Long,
) {
    constructor(db: AenhtDatabase, nh: NhApi, eh: EhApi) : this(db, nh, eh, { Clock.System.now().toEpochMilliseconds() })

    private val lock = Mutex()

    @Throws(CancellationException::class)
    suspend fun all(): List<FavoriteRecord> =
        (db.favorites().bySite(Site.NHENTAI.key) + db.favorites().bySite(Site.EHENTAI.key)).sortedByDescending { it.favoritedAt }

    private fun record(card: GallerySummary, folder: Int, time: Long, exact: Boolean) = FavoriteRecord(
        key = card.key.storageKey, site = card.site.key, galleryId = card.key.id, token = card.key.token,
        mediaId = card.mediaId, title = card.title, thumbnail = card.thumbnail,
        thumbWidth = card.thumbWidth, thumbHeight = card.thumbHeight, numPages = card.pages,
        folder = folder, source = "account", favoritedAt = time, exactTime = exact,
        tagIds = card.nhTagIds.joinToString(","),
    )

    /** Favorited (or moved to [folder]) in the app: the time is known. */
    @Throws(CancellationException::class)
    suspend fun added(card: GallerySummary, folder: Int) = lock.withLock {
        val previous = db.favorites().get(card.key.storageKey)
        db.favorites().upsert(listOf(record(card, folder, previous?.favoritedAt ?: now(), previous?.exactTime ?: true)))
    }

    @Throws(CancellationException::class)
    suspend fun removed(key: GalleryKey) = lock.withLock {
        db.favorites().delete(key.storageKey)
        db.tombstones().upsert(listOf(Tombstone(Tombstone.FAVORITE, key.storageKey, now())))
    }

    /** Reads all of a site's favorites and updates the copy (galleries no longer favorited are removed). */
    @Throws(Exception::class)
    suspend fun sync(site: Site, progress: SyncProgress?) {
        val fetched = fetchAll(site, progress)
        lock.withLock { merge(site, fetched) }
    }

    private suspend fun fetchAll(site: Site, progress: SyncProgress?): List<Pair<GallerySummary, Int>> {
        val out = mutableListOf<Pair<GallerySummary, Int>>()
        if (site == Site.NHENTAI) {
            var page = 1
            while (true) {
                val r = nh.favorites(page)
                out += r.result.map { GallerySummary.from(it) to 0 }
                progress?.update(out.size)
                if (page >= r.numPages || r.result.isEmpty()) break
                page++
            }
        } else {
            var cursor: String? = null
            while (true) {
                val r = eh.list(EhListSource.Favorites(null), cursor)
                out += r.galleries.map { GallerySummary.from(it) to (it.favoriteFolder ?: 0) }
                progress?.update(out.size)
                if (r.galleries.isEmpty()) break
                cursor = r.nextCursor ?: break
            }
        }
        return out
    }

    private suspend fun merge(site: Site, fetched: List<Pair<GallerySummary, Int>>) {
        val dao = db.favorites()
        val existing = dao.bySite(site.key).associateBy { it.key }
        val firstSync = existing.isEmpty()
        val t = now()
        val merged = fetched.mapIndexed { index, (card, folder) ->
            val old = existing[card.key.storageKey]
            when {
                old != null -> record(card, folder, old.favoritedAt, old.exactTime)
                // First sync: the site's order, spread a second apart before now.
                firstSync -> record(card, folder, t - index * 1000L, exact = false)
                // Seen for the first time now: favorited since the last sync.
                else -> record(card, folder, t - index, exact = true)
            }
        }
        dao.upsert(merged)
        val kept = merged.map { it.key }.toSet()
        existing.keys.filter { it !in kept }.forEach { dao.delete(it) }
    }
}
