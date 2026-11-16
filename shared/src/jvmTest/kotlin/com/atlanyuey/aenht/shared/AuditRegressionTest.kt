// Audit-only regression tests, added through an external Gradle init script.
// They exercise the unmodified shared module and are expected to FAIL on the
// initial 1a849ec baseline; they pass on the rechecked fbeabb7.
// They do not access real sites, accounts or devices.
package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.HistoryRepository
import com.atlanyuey.aenht.shared.db.Tombstone
import com.atlanyuey.aenht.shared.db.openInMemoryDatabase
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.sync.SyncFile
import com.atlanyuey.aenht.shared.sync.WebDavSync
import com.atlanyuey.aenht.shared.sync.WebDavException
import com.atlanyuey.aenht.shared.app.UpdateChecker
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class AuditRegressionTest {
    @Test
    fun olderRemoteTombstoneMustNotResurrectHistory() = runTest {
        val db = openInMemoryDatabase()
        try {
            val repo = HistoryRepository(db) { 200L }
            val key = GalleryKey.nh(1)
            val oldRecord = repo.record(key, "synthetic title")
            db.history().delete(key.storageKey)
            db.tombstones().upsert(listOf(Tombstone(Tombstone.HISTORY, key.storageKey, 300L)))
            val http = buildHttpClient(MockEngine { respond("{}") })
            try {
                WebDavSync(db, http) { 1_000L }.merge(
                    SyncFile(
                        history = listOf(oldRecord),
                        tombstones = listOf(Tombstone(Tombstone.HISTORY, key.storageKey, 100L)),
                    ), 0L,
                )
                println("after_merge deletedAt=${db.tombstones().all().single().deletedAt}, historyPresent=${db.history().get(key.storageKey) != null}")
                assertEquals(300L, db.tombstones().all().single().deletedAt)
                assertNull(db.history().get(key.storageKey))
            } finally { http.close() }
        } finally { db.close() }
    }

    @Test
    fun githubRateLimitMustNotBecomeAnEmptySuccessfulUpdateCheck() = runTest {
        val http = buildHttpClient(MockEngine {
            respond("""{"message":"API rate limit exceeded"}""", HttpStatusCode.Forbidden)
        })
        try {
            assertFailsWith<Exception> { UpdateChecker(http).check("0.1.0") }
        } finally { http.close() }
    }

    @Test
    fun futureSyncVersionMustNotBeSilentlyRewritten() = runTest {
        var writes = 0
        val http = buildHttpClient(MockEngine { request ->
            when (request.method.value) {
                "GET" -> respond("""{"version":999,"history":[],"future_field":"keep-me"}""")
                "PUT" -> { writes++; respond("", HttpStatusCode.Created) }
                else -> respond("", HttpStatusCode.OK)
            }
        })
        val db = openInMemoryDatabase()
        try {
            val sync = WebDavSync(db, http) { 1_000L }
            assertFailsWith<WebDavException> {
                sync.sync("https://example.invalid/dav", "fake-user", "fake-password", "fixture", "{}", 0L)
                println("future_version_writes=$writes")
            }
            assertEquals(0, writes)
        } finally { db.close(); http.close() }
    }
}
