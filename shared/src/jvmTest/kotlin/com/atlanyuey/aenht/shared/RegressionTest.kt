package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.app.Diagnostics
import com.atlanyuey.aenht.shared.app.UpdateCheckException
import com.atlanyuey.aenht.shared.app.UpdateChecker
import com.atlanyuey.aenht.shared.db.HistoryRepository
import com.atlanyuey.aenht.shared.db.Tombstone
import com.atlanyuey.aenht.shared.db.openInMemoryDatabase
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhCredentialStorage
import com.atlanyuey.aenht.shared.eh.EhException
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhParser
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.reader.Spreads
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.SearchHistory
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.SiteLinks
import com.atlanyuey.aenht.shared.sync.SyncFile
import com.atlanyuey.aenht.shared.sync.WebDavException
import com.atlanyuey.aenht.shared.sync.WebDavSync
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases from the code review of 2026-11-15 (docs: the audit's findings
 * F001–F050): each failed on the reviewed version.
 */
class RegressionTest {
    // MARK: Links (F001, F002)

    @Test
    fun linksWithHugeNumbersAreNotLinks() {
        assertNull(SiteLinks.find("https://e-hentai.org/g/999999999999999999999999999999/abcdef1234/"))
        assertNull(SiteLinks.find("https://e-hentai.org/s/abcdef1234/123-99999999999999999999999999999"))
        assertNull(SiteLinks.find("https://e-hentai.org/s/abcdef1234/99999999999999999999999999999-3"))
        assertNull(SiteLinks.find("https://nhentai.net/g/1234567890123/"))
        assertNull(SiteLinks.find("https://nhentai.net/g/0/"))
        assertNull(EhParser.galleryKey("https://e-hentai.org/g/999999999999999999999999999999/abcdef1234/"))
        assertNull(EhParser.imagePageKey("https://e-hentai.org/s/abcdef1234/123-99999999999999999999999999999"))
        assertNull(SiteLinks.fromSearchText("9".repeat(4000)))
    }

    @Test
    fun onlyTheSitesOwnHostsAreLinks() {
        assertNull(SiteLinks.find("https://evilnhentai.net/g/123/"))
        assertNull(SiteLinks.find("https://x.nhentai.net/g/123/"))
        assertNull(SiteLinks.find("https://evil.com/nhentai.net/g/123/"))
        assertNull(SiteLinks.find("https://user@nhentai.net/g/123/"))
        assertNull(SiteLinks.find("note-e-hentai.org/g/2345678/abcdef1234/"))
        // Still links: with or without a scheme, after any text.
        assertEquals(GalleryLink.Nh(123), SiteLinks.find("nhentai.net/g/123"))
        assertEquals(GalleryLink.Nh(123), SiteLinks.find("www.nhentai.net/g/123/"))
        assertEquals(GalleryLink.Nh(123), SiteLinks.find("看这个https://nhentai.net/g/123/"))
        assertEquals(GalleryLink.Nh(123), SiteLinks.find("看这个 nhentai.net/g/123/"))
        assertEquals(GalleryLink.Nh(123), SiteLinks.find("(nhentai.net/g/123)"))
        assertEquals(GalleryLink.Eh(2345678, "abcdef1234", true), SiteLinks.find("HTTPS://EXHENTAI.ORG/g/2345678/ABCDEF1234/"))
    }

    // MARK: Reader (F003)

    @Test
    fun aGalleryWithoutPagesHasNoSpreads() {
        for (pages in listOf(0, -3)) {
            val spreads = Spreads(pages, cover = true, widePages = emptySet())
            assertEquals(0, spreads.count)
            assertEquals(0, spreads.slotOf(0))
            assertEquals(0, spreads.slotOf(7))
            assertEquals(-1, spreads.step(0, 1))
            assertEquals(emptyList(), spreads.pages(0))
        }
        assertEquals(0, Spreads(1, cover = false, widePages = emptySet()).slotOf(-5))
    }

    // MARK: Sync (F005, F006, F044)

    @Test
    fun anOlderDeletionFromTheServerDoesNotBringHistoryBack() = runTest {
        val db = openInMemoryDatabase()
        val key = GalleryKey.nh(1)
        val read = HistoryRepository(db) { 200L }.record(key, "title")
        db.history().delete(key.storageKey)
        db.tombstones().upsert(listOf(Tombstone(Tombstone.HISTORY, key.storageKey, 300L)))
        WebDavSync(db, buildHttpClient(MockEngine { respond("{}") })) { 1_000L }.merge(
            SyncFile(history = listOf(read), tombstones = listOf(Tombstone(Tombstone.HISTORY, key.storageKey, 100L))), 0L,
        )
        assertEquals(300L, db.tombstones().all().single().deletedAt)
        assertNull(db.history().get(key.storageKey))
        // A newer deletion from the server still counts.
        WebDavSync(db, buildHttpClient(MockEngine { respond("{}") })) { 1_000L }.merge(
            SyncFile(tombstones = listOf(Tombstone(Tombstone.HISTORY, key.storageKey, 400L))), 0L,
        )
        assertEquals(400L, db.tombstones().all().single().deletedAt)
        db.close()
    }

    @Test
    fun aFileOfANewerVersionIsNotRewritten() = runTest {
        var writes = 0
        val http = buildHttpClient(MockEngine { request ->
            when (request.method.value) {
                "GET" -> respond("""{"version":999,"history":[],"future_field":"keep-me"}""")
                "PUT" -> { writes++; respond("", HttpStatusCode.Created) }
                else -> respond("", HttpStatusCode.OK)
            }
        })
        val db = openInMemoryDatabase()
        val error = assertFailsWith<WebDavException> {
            WebDavSync(db, http) { 1_000L }.sync("https://example.invalid/dav", "u", "p", "device", "{}", 0L)
        }
        assertTrue(error.newerVersion)
        assertEquals(0, writes)
        db.close()
    }

    /** A server with ETags that refuses an upload over a copy that changed (412). */
    private class EtagServer {
        var body: String? = null
        var etag = 0
        val conditions = mutableListOf<String>()
        /** Runs once, between a device's download and its upload. */
        var beforePut: (suspend () -> Unit)? = null
        val engine = MockEngine { request ->
            when (request.method.value) {
                "MKCOL" -> respond("", HttpStatusCode.Created)
                "GET" -> body?.let { respond(it, HttpStatusCode.OK, headersOf("ETag", "\"v$etag\"")) } ?: respond("", HttpStatusCode.NotFound)
                "PUT" -> {
                    beforePut?.also { beforePut = null }?.invoke()
                    val ifMatch = request.headers["If-Match"]
                    val ifNoneMatch = request.headers["If-None-Match"]
                    conditions += ifMatch?.let { "If-Match" } ?: ifNoneMatch?.let { "If-None-Match" } ?: "none"
                    val stale = (ifMatch != null && ifMatch != "\"v$etag\"") || (ifNoneMatch == "*" && body != null)
                    if (stale) respond("", HttpStatusCode.PreconditionFailed) else {
                        body = (request.body as TextContent).text
                        etag++
                        respond("", HttpStatusCode.NoContent)
                    }
                }
                else -> respond("", HttpStatusCode.BadRequest)
            }
        }
    }

    @Test
    fun anUploadDoesNotOverwriteAnotherDevicesNewerCopy() = runTest {
        val server = EtagServer()
        val dbA = openInMemoryDatabase(); val dbB = openInMemoryDatabase()
        val a = WebDavSync(dbA, buildHttpClient(server.engine)) { 5_000L }
        val b = WebDavSync(dbB, buildHttpClient(server.engine)) { 5_000L }
        val url = "https://dav.example.com/files"
        HistoryRepository(dbA) { 100L }.record(GalleryKey.nh(1), "from A")
        a.sync(url, "u", "p", "A", "{}", 0L)
        assertEquals(listOf("If-None-Match"), server.conditions)

        // A syncs again; B uploads its own history after A's download and before A's upload.
        HistoryRepository(dbA) { 200L }.record(GalleryKey.nh(2), "from A too")
        HistoryRepository(dbB) { 300L }.record(GalleryKey.nh(3), "from B")
        server.beforePut = { b.sync(url, "u", "p", "B", "{}", 0L) }
        a.sync(url, "u", "p", "A", "{}", 0L)

        // A's first upload was refused, it merged B's copy and uploaded again: nothing is lost.
        assertTrue("If-Match" in server.conditions)
        val onServer = server.body!!
        for (title in listOf("from A", "from A too", "from B")) assertTrue(title in onServer, title)
        assertNotNull(dbA.history().get(GalleryKey.nh(3).storageKey))
        dbA.close(); dbB.close()
    }

    // MARK: Updates (F033)

    @Test
    fun aRefusedUpdateCheckIsAnErrorNotAnEmptyList() = runTest {
        val refused = buildHttpClient(MockEngine { respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden) })
        assertFailsWith<UpdateCheckException> { UpdateChecker(refused).check("0.1.0") }
        val notAList = buildHttpClient(MockEngine { respond("""{"message":"moved"}""") })
        assertFailsWith<UpdateCheckException> { UpdateChecker(notAList).check("0.1.0") }
        val none = buildHttpClient(MockEngine { respond("[]") })
        assertNull(UpdateChecker(none).check("0.1.0").latest)
    }

    // MARK: Diagnostics (F024)

    @Test
    fun theLogHidesCredentialsInHeadersAndLinks() {
        fun hidden(text: String, secret: String) = assertFalse(secret in Diagnostics.redact(text), Diagnostics.redact(text))
        hidden("Authorization: Basic ZmFrZTpmYWtl", "ZmFrZTpmYWtl")
        hidden("authorization: Token abc123456", "abc123456")
        hidden("Cookie: sessionid=topsecret; other=1", "topsecret")
        hidden("https://example.invalid/?api_key=DEMO_FAKE_KEY&password=DEMO_FAKE_PASSWORD", "DEMO_FAKE")
        hidden("GET https://example.invalid/v1?x=1&token=tok_12345 failed", "tok_12345")
        hidden("https://someone:hunter2pass@dav.example.com/files", "hunter2pass")
        hidden("""{"Authorization":"Bearer abcdef"}""", "abcdef")
        // What isn't a secret stays readable.
        assertEquals("GET https://e-hentai.org/g/1/ → 404", Diagnostics.redact("GET https://e-hentai.org/g/1/ → 404"))
        assertEquals("monkey=banana", Diagnostics.redact("monkey=banana"))
    }

    // MARK: Favorites copy (F012, F016)

    private class Credentials : EhCredentialStorage {
        override var memberId: String? = "1"
        override var passHash: String? = "h"
        override var igneous: String? = null
        override fun save(memberId: String, passHash: String, igneous: String?) {}
        override fun clear() {}
    }

    @Test
    fun aPageThatIsNotTheFavoritesIsNotAnEmptyFavoritesList() = runTest {
        val loginPage = "<html><body><form action=\"https://forums.e-hentai.org/index.php?act=Login\"></form></body></html>"
        val api = EhApi(Credentials(), buildHttpClient(MockEngine { respond(loginPage) }))
        assertFailsWith<EhException.Parse> { api.list(EhListSource.Favorites(null)) }
        // The favorites page of an account without favorites is an empty list.
        val empty = "<html><body><div class=\"fp\"><div>0</div><div class=\"i\"></div><div>Favorites 0</div></div><p>No hits found</p></body></html>"
        val api2 = EhApi(Credentials(), buildHttpClient(MockEngine { respond(empty) }))
        assertEquals(emptyList(), api2.list(EhListSource.Favorites(null)).galleries)
    }

    // MARK: Search history (F035)

    @Test
    fun searchHistoryEntriesCarryTheirSite() {
        val nh = SearchHistory.entry(Site.NHENTAI, " artist:someone ")
        assertEquals(Site.NHENTAI, SearchHistory.site(nh))
        assertEquals("artist:someone", SearchHistory.query(nh))
        // An entry saved before sites were recorded.
        assertNull(SearchHistory.site("artist:someone"))
        assertEquals("artist:someone", SearchHistory.query("artist:someone"))
        val eh = SearchHistory.entry(Site.EHENTAI, "artist:someone")
        // The same words on the other site are another entry; the old site-less copy is replaced.
        assertEquals(listOf(eh, nh), SearchHistory.adding(listOf(nh, "artist:someone"), eh, limit = 20))
        assertEquals(listOf(nh, eh), SearchHistory.adding(listOf(eh, nh), nh, limit = 20))
        assertEquals(1, SearchHistory.adding(listOf(eh, nh), nh, limit = 1).size)
    }
}
