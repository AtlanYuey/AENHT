package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.net.RateLimit
import com.atlanyuey.aenht.shared.net.RateLimiter
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.nh.NhApi
import com.atlanyuey.aenht.shared.nh.NhException
import com.atlanyuey.aenht.shared.nh.ProofOfWork
import com.atlanyuey.aenht.shared.nh.TokenStorage
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GalleryKeyTest {
    @Test
    fun roundTrips() {
        val nh = GalleryKey.nh(177013)
        assertEquals("nh:177013", nh.storageKey)
        assertEquals(nh, GalleryKey.parse(nh.storageKey))
        val eh = GalleryKey(Site.EHENTAI, 2345678, "abcdef1234")
        assertEquals("eh:2345678:abcdef1234", eh.storageKey)
        assertEquals(eh, GalleryKey.parse(eh.storageKey))
        assertNull(GalleryKey.parse("xx:1"))
        assertNull(GalleryKey.parse("nh:abc"))
    }
}

class RateLimiterTest {
    @Test
    fun waitsWhenTheWindowIsFull() = runTest {
        var clock = 0L
        val limiter = RateLimiter(now = { clock })
        val waits = mutableListOf<Long>()
        limiter.onWait = { _, ms -> waits += ms; clock += ms }
        val limit = RateLimit(2, 10.seconds)
        limiter.acquire("a", limit)
        limiter.acquire("a", limit)
        assertTrue(waits.isEmpty())
        limiter.acquire("a", limit) // third one in the window waits ~10 s
        assertEquals(1, waits.size)
        assertTrue(waits[0] in 9_000..10_001)
        // Other buckets are independent.
        limiter.acquire("b", limit)
        assertEquals(1, waits.size)
    }
}

class ProofOfWorkTest {
    @Test
    fun findsANonceWithLeadingZeroBits() {
        val nonce = ProofOfWork.solve("challenge", 12)
        val hash = "challenge$nonce".encodeUtf8().sha256()
        assertEquals(0, hash[0].toInt())
        assertEquals(0, hash[1].toInt() and 0xF0)
    }
}

private class MemoryTokens(override var accessToken: String?, override var refreshToken: String?) : TokenStorage {
    override fun save(access: String, refresh: String?) {
        accessToken = access
        if (refresh != null) refreshToken = refresh
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
    }
}

class NhApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val page = """{"result":[{"id":1,"media_id":"9","english_title":"A","tag_ids":[6346]}],"num_pages":3}"""

    @Test
    fun renewsTheTokenOnceAndReplaysReads() = runTest {
        val tokens = MemoryTokens("old", "refresh")
        var refreshes = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/auth/refresh") -> {
                    refreshes++
                    respond("""{"access_token":"new","refresh_token":"r2"}""", HttpStatusCode.OK, json)
                }
                request.headers[HttpHeaders.Authorization] == "User old" -> respond("{}", HttpStatusCode.Unauthorized, json)
                else -> respond(page, HttpStatusCode.OK, json)
            }
        }
        val api = NhApi(tokens, buildHttpClient(engine))
        val result = api.favorites(1)
        assertEquals(1, result.result.size)
        assertEquals("new", tokens.accessToken)
        assertEquals("r2", tokens.refreshToken)
        assertEquals(1, refreshes)
    }

    @Test
    fun keepsTheAccountWhenRenewalFails() = runTest {
        val tokens = MemoryTokens("old", "refresh")
        var flagged = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/refresh")) respond("{}", HttpStatusCode.Unauthorized, json)
            else respond("{}", HttpStatusCode.Unauthorized, json)
        }
        val api = NhApi(tokens, buildHttpClient(engine))
        api.onNeedsVerification = { flagged = true }
        assertFailsWith<NhException.NeedsVerification> { api.favorites(1) }
        assertTrue(flagged)
        assertEquals("old", tokens.accessToken) // not logged out
    }

    @Test
    fun parsesLatestAndSearch() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath + "?" + request.url.encodedQuery
            assertEquals(HttpMethod.Get, request.method)
            respond(page, HttpStatusCode.OK, json)
        }
        val api = NhApi(MemoryTokens(null, null), buildHttpClient(engine))
        val latest = api.galleries("", "date", 2)
        assertEquals(3, latest.numPages)
        assertEquals("JP", latest.result[0].language?.badge)
        api.galleries("tag:\"a\"", "popular", 1)
        assertTrue(seen[0].startsWith("/api/v2/galleries?page=2"))
        assertTrue(seen[1].startsWith("/api/v2/search?"))
    }
}
