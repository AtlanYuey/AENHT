package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.db.openInMemoryDatabase
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhCredentialStorage
import com.atlanyuey.aenht.shared.eh.EhException
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.site.SiteLinks
import com.atlanyuey.aenht.shared.sync.WebDavSync
import com.atlanyuey.aenht.shared.sync.WebDavException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in tests against the unchanged product module. Synthetic data only.
class RecheckRegressionTest {
    @Test
    fun persistentConflictsMustNeverFallBackToAnUnconditionalOverwrite() = runTest {
        val conditions = mutableListOf<String?>()
        var reads = 0
        val http = buildHttpClient(MockEngine { request ->
            when (request.method.value) {
                "GET" -> { reads++; respond("{}", HttpStatusCode.OK, headersOf("ETag", "\"v$reads\"")) }
                "PUT" -> {
                    val condition = request.headers["If-Match"]
                    conditions += condition
                    if (condition == null) respond("", HttpStatusCode.NoContent)
                    else respond("", HttpStatusCode.PreconditionFailed)
                }
                else -> respond("", HttpStatusCode.Created)
            }
        })
        val db = openInMemoryDatabase()
        try {
            val error = runCatching {
                WebDavSync(db, http) { 1000L }.sync("https://example.invalid/dav", "fake", "fake", "fixture", "{}", 0L)
            }.exceptionOrNull()
            println("persistent_conflict_put_conditions=$conditions; final_error=${error?.javaClass?.simpleName}")
            assertTrue(conditions.none { it == null }, "A conflict must not disable If-Match: $conditions")
            assertTrue(error is WebDavException, "Exhaustion must report an error")
        } finally { db.close(); http.close() }
    }

    @Test
    fun aSiteUrlEmbeddedInsideAnotherUrlsPathMustNotBeRecognised() {
        val text = "https://example.invalid/path/https://nhentai.net/g/123/"
        println("nested_url_result=${SiteLinks.find(text)}")
        assertNull(SiteLinks.find(text))
    }

    @Test
    fun aMissingFavoriteNoteFormMustNotBecomeAnEmptyNote() = runTest {
        val credentials = object : EhCredentialStorage {
            override var memberId: String? = "1"
            override var passHash: String? = "fake"
            override var igneous: String? = null
            override fun save(memberId: String, passHash: String, igneous: String?): Boolean = true
            override fun updateIgneous(memberId: String, igneous: String): Boolean = this.memberId == memberId
            override fun clear() {}
        }
        val valid = buildHttpClient(MockEngine { respond("<textarea name=\"favnote\"></textarea>") })
        val invalid = buildHttpClient(MockEngine { respond("<html><body>Temporary gateway response</body></html>") })
        try {
            assertEquals("", EhApi(credentials, valid).favoriteNote(1L, "abcdef1234"))
            val api = EhApi(credentials, invalid)
            val answer = runCatching { api.favoriteNote(1L, "abcdef1234") }
            println("missing_note_form success=${answer.isSuccess}, note=${answer.getOrNull()}")
            assertFailsWith<EhException.Parse> { api.favoriteNote(1L, "abcdef1234") }
        } finally { valid.close(); invalid.close() }
    }
}
