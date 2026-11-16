package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.*
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.nh.NhApi
import com.atlanyuey.aenht.shared.reader.Spreads
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.sync.SyncFile
import com.atlanyuey.aenht.shared.sync.WebDavSync
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Additional current-main contracts, using real repositories and controlled HTTP. */
class MainFunctionalSweepTest {
    @Test fun rereadingAfterDeletionSurvivesAnOlderRemoteReadAndDeletion() = runTest {
        val db = openInMemoryDatabase()
        val http = buildHttpClient(MockEngine { respond("{}") })
        var clock = 200L
        try {
            val repo = HistoryRepository(db) { clock }
            val key = GalleryKey.nh(990003)
            val old = repo.record(key, "Synthetic history", page = 2)
            clock = 300L
            repo.delete(key)
            val deletion = db.tombstones().all()
            clock = 400L
            repo.record(key, "Synthetic reread", page = 7)
            WebDavSync(db, http) { 500L }.merge(SyncFile(history = listOf(old), tombstones = deletion), 0L)
            assertEquals(7, db.history().get(key.storageKey)?.lastPage)
            assertEquals(400L, db.history().get(key.storageKey)?.readAt)
        } finally { http.close(); db.close() }
    }

    private fun favorite(id: Int, time: Long) = FavoriteRecord(
        key = "nh:$id", site = "nh", galleryId = id.toLong(), token = null,
        mediaId = "9", title = "Synthetic favorite $id", thumbnail = null,
        thumbWidth = 0, thumbHeight = 0, numPages = 1, folder = 0,
        source = "account", favoritedAt = time, exactTime = true,
    )

    @Test fun aFailedLaterFavoritesPagePreservesTheExistingMirror() = runTest {
        val db = openInMemoryDatabase()
        val pages = mutableListOf<Int>()
        val http = buildHttpClient(MockEngine { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 1
            pages += page
            if (page == 1) respond("""{"result":[{"id":2,"media_id":"9","english_title":"New"}],"num_pages":2}""")
            else respond("{}", HttpStatusCode.InternalServerError)
        })
        try {
            db.favorites().upsert(listOf(favorite(1, 123L)))
            val mirror = FavoritesMirror(db, NhApi(MemoryTokens("key:fixture", null), http), EhApi(MemoryEhCredentials(), http)) { 1_000L }
            assertTrue(runCatching { mirror.sync(Site.NHENTAI, null) }.isFailure)
            assertEquals(listOf(1, 2), pages)
            assertEquals(listOf("nh:1"), mirror.all().map { it.key })
            assertEquals(123L, mirror.all().single().favoritedAt)
        } finally { http.close(); db.close() }
    }

    @Test fun successfulFavoritesSyncKeepsKnownTimesAndAddsNewFavorites() = runTest {
        val db = openInMemoryDatabase()
        val http = buildHttpClient(MockEngine {
            respond("""{"result":[{"id":2,"media_id":"9","english_title":"New"},{"id":1,"media_id":"9","english_title":"Updated"}],"num_pages":1}""")
        })
        try {
            db.favorites().upsert(listOf(favorite(1, 123L), favorite(3, 100L)))
            val mirror = FavoritesMirror(db, NhApi(MemoryTokens("key:fixture", null), http), EhApi(MemoryEhCredentials(), http)) { 1_000L }
            mirror.sync(Site.NHENTAI, null)
            val saved = mirror.all()
            assertEquals(listOf("nh:2", "nh:1"), saved.map { it.key })
            assertEquals(123L, saved.last().favoritedAt)
            assertEquals("Updated", saved.last().title)
            assertEquals(1_000L, saved.first().favoritedAt)
        } finally { http.close(); db.close() }
    }

    @Test fun spreadNavigationKeepsEveryRealPageReachableAcrossPairingModes() {
        for (count in 0..257) for (cover in listOf(false, true)) for (single in listOf(false, true)) {
            val wide = (0 until count).filter { it % 7 == 3 }.toSet()
            val spreads = Spreads(count, cover, wide, single)
            assertTrue(spreads.pages(-1).isEmpty())
            assertTrue(spreads.pages(spreads.count).isEmpty())
            if (count == 0) assertEquals(-1, spreads.step(0, 1))
            for (page in 0 until count) {
                val pages = spreads.pages(spreads.slotOf(page))
                assertTrue(page in pages, "unreachable page=$page count=$count cover=$cover single=$single")
                assertTrue(pages.all { it in 0 until count })
                if (page in wide) assertEquals(listOf(page), pages)
            }
        }
    }
}
