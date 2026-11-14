package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.HistoryRepository
import com.atlanyuey.aenht.shared.db.openInMemoryDatabase
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.sync.WebDavException
import com.atlanyuey.aenht.shared.sync.WebDavSync
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavTest {
    /** A WebDAV server with one folder; the password is "pw". */
    private class FakeServer {
        val files = HashMap<String, String>()
        var folder = false
        val engine = MockEngine { request ->
            if (request.headers["Authorization"] != "Basic dXNlcjpwdw==") return@MockEngine respond("", HttpStatusCode.Unauthorized)
            val path = request.url.encodedPath
            when (request.method.value) {
                "PROPFIND" -> respond("", HttpStatusCode.MultiStatus)
                "MKCOL" -> if (folder) respond("", HttpStatusCode.MethodNotAllowed) else { folder = true; respond("", HttpStatusCode.Created) }
                "GET" -> files[path]?.let { respond(it, HttpStatusCode.OK) } ?: respond("", HttpStatusCode.NotFound)
                "PUT" -> { files[path] = (request.body as TextContent).text; respond("", HttpStatusCode.Created) }
                else -> respond("", HttpStatusCode.BadRequest)
            }
        }
    }

    @Test
    fun twoDevicesMergeHistoryDeletionsAndSettings() = runTest {
        val server = FakeServer()
        var clock = 1_000_000L
        val dbA = openInMemoryDatabase(); val dbB = openInMemoryDatabase()
        val a = WebDavSync(dbA, buildHttpClient(server.engine)) { clock }
        val b = WebDavSync(dbB, buildHttpClient(server.engine)) { clock }
        val historyA = HistoryRepository(dbA) { clock++ }
        val historyB = HistoryRepository(dbB) { clock++ }
        val url = "https://dav.example.com/remote.php/dav/files/user"

        assertFailsWith<WebDavException> { a.test(url, "user", "wrong") }.also { assertTrue(it.unauthorized) }
        a.test(url, "user", "pw")

        val g1 = GalleryKey.nh(1); val g2 = GalleryKey(Site.EHENTAI, 2, "tok"); val g3 = GalleryKey.nh(3)
        historyA.record(g1, "one", page = 3)
        historyA.record(g2, "two")
        val first = a.sync(url, "user", "pw", "A", """{"theme":"dark"}""", settingsChangedAt = 10)
        assertNull(first.settings)
        assertTrue(server.files.keys.single().endsWith("/AENHT/sync.json"))

        // B gets A's history and newer settings, reads g1 further, deletes g2 and adds g3.
        val second = b.sync(url, "user", "pw", "B", """{"theme":"light"}""", settingsChangedAt = 5)
        assertEquals("""{"theme":"dark"}""", second.settings)
        assertEquals(setOf(g1, g2), historyB.all().map { it.galleryKey }.toSet())
        historyB.record(g1, "", page = 9)
        historyB.delete(g2)
        historyB.record(g3, "three")
        b.sync(url, "user", "pw", "B", """{"theme":"dark"}""", settingsChangedAt = 10)

        // A takes the later progress, the deletion and the new gallery.
        a.sync(url, "user", "pw", "A", """{"theme":"dark"}""", settingsChangedAt = 10)
        val all = historyA.all().associateBy { it.galleryKey }
        assertEquals(setOf(g1, g3), all.keys)
        assertEquals(9, all.getValue(g1).lastPage)

        // Clearing history on A clears it on B.
        historyA.clear()
        a.sync(url, "user", "pw", "A", "{}", settingsChangedAt = 10)
        b.sync(url, "user", "pw", "B", "{}", settingsChangedAt = 10)
        assertTrue(historyB.all().isEmpty())
    }
}
