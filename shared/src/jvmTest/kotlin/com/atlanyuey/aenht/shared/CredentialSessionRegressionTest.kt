// Historical behavior assertions; adapters updated to the new TokenStorage/EhCredentialStorage contracts.
package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhCredentialStorage
import com.atlanyuey.aenht.shared.nh.NhApi
import com.atlanyuey.aenht.shared.nh.TokenStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Tests the actual shared API, with fake credentials and controlled HTTP.
class CredentialSessionRegressionTest {
    private class Tokens(
        @Volatile override var accessToken: String?,
        @Volatile override var refreshToken: String?,
        val rejectWrites: Boolean = false,
    ) : TokenStorage {
        private var revision = 0L
        override fun snapshot() = com.atlanyuey.aenht.shared.nh.TokenSnapshot(accessToken, refreshToken, revision)
        fun save(access: String, refresh: String?) {
            if (rejectWrites) return
            accessToken = access
            if (refresh != null) refreshToken = refresh
            revision++
        }
        @Synchronized
        override fun replace(expected: com.atlanyuey.aenht.shared.nh.TokenSnapshot, access: String, refresh: String?): Boolean {
            if (rejectWrites || snapshot() != expected) return false
            accessToken = access; refreshToken = refresh; revision++; return true
        }
        @Synchronized
        override fun renew(expected: com.atlanyuey.aenht.shared.nh.TokenSnapshot, access: String, refresh: String?): Boolean {
            if (rejectWrites || snapshot() != expected) return false
            accessToken = access; if (refresh != null) refreshToken = refresh; return true
        }
        @Synchronized
        override fun clear() { accessToken = null; refreshToken = null; revision++ }
    }

    @Test
    fun failedApiKeyCheckMustNotUndoALaterLogout() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = Tokens("key:previous", null)
        val http = buildHttpClient(MockEngine {
            entered.complete(Unit)
            release.await()
            respond("{}", HttpStatusCode.ServiceUnavailable)
        })
        try {
            val checking = async { runCatching { NhApi(tokens, http).loginWithApiKey("candidate") } }
            entered.await()
            tokens.clear() // The same storage action used by Session.logout, after its generation changes.
            release.complete(Unit)
            checking.await()
            println("after_logout_and_old_key_failure access=${tokens.accessToken}")
            assertNull(tokens.accessToken, "An old key check must not restore a logged-out session")
        } finally { http.close() }
    }

    @Test
    fun successfulOldKeyCheckMustNotUndoSwitchAwayAndBack() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = Tokens("key:A", null)
        val http = buildHttpClient(MockEngine {
            entered.complete(Unit); release.await()
            respond("""{"id":3,"username":"candidate-C","avatar_url":""}""")
        })
        try {
            val checking = async { runCatching { NhApi(tokens, http).loginWithApiKey("C") } }
            entered.await()
            // Saved API keys are stable: switching away and back restores the same token.
            // Session.generation changes on each switch, but the shared write precedes its check.
            tokens.clear(); tokens.save("key:B", null)
            tokens.clear(); tokens.save("key:A", null)
            release.complete(Unit)
            checking.await()
            println("after_A_B_A_and_old_C_check access=${tokens.accessToken}")
            assertEquals("key:A", tokens.accessToken, "The latest explicit account switch must win")
        } finally { http.close() }
    }

    @Test
    fun oldRefreshResponseMustNotOverwriteANewerLogin() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = Tokens("A-old", "A-refresh")
        val http = buildHttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/auth/refresh") -> {
                    entered.complete(Unit); release.await()
                    respond("""{"access_token":"A-renewed","refresh_token":"A-refresh-new"}""")
                }
                request.headers["Authorization"] == "User A-old" -> respond("{}", HttpStatusCode.Unauthorized)
                else -> respond("""{"id":1,"username":"synthetic","avatar_url":""}""")
            }
        })
        try {
            val reading = async { runCatching { NhApi(tokens, http).currentUser() } }
            entered.await()
            tokens.clear(); tokens.save("key:B", null)
            release.complete(Unit)
            reading.await()
            println("after_B_login_and_A_refresh access=${tokens.accessToken}, refresh=${tokens.refreshToken}")
            assertEquals("key:B", tokens.accessToken)
        } finally { http.close() }
    }

    @Test
    fun aRejectedCredentialWriteMustNotEraseThePreviousLogin() = runTest {
        val tokens = Tokens("old-access", "old-refresh", rejectWrites = true)
        val http = buildHttpClient(MockEngine { respond("""{"id":2,"username":"candidate","avatar_url":""}""") })
        try {
            runCatching { NhApi(tokens, http).loginWithApiKey("candidate") }
            println("after_rejected_key_write access=${tokens.accessToken}, refresh=${tokens.refreshToken}")
            assertEquals("old-access", tokens.accessToken)
            assertEquals("old-refresh", tokens.refreshToken)
        } finally { http.close() }
    }

    @Test
    fun anOldExHentaiResponseMustNotWriteCookiesIntoANewerLogin() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val credentials = object : EhCredentialStorage {
            override var memberId: String? = "A"
            override var passHash: String? = "A-hash"
            override var igneous: String? = "A-cookie"
            override fun save(memberId: String, passHash: String, igneous: String?): Boolean {
                this.memberId = memberId; this.passHash = passHash; this.igneous = igneous; return true
            }
            override fun updateIgneous(memberId: String, igneous: String): Boolean {
                if (this.memberId != memberId) return false
                this.igneous = igneous; return true
            }
            override fun clear() { memberId = null; passHash = null; igneous = null }
        }
        val http = buildHttpClient(MockEngine { request ->
            if (request.url.host == "e-hentai.org") respond("Image Limits") else {
                entered.complete(Unit); release.await()
                respond("ok", HttpStatusCode.OK, headersOf("Set-Cookie", "igneous=A-new-cookie; Path=/"))
            }
        })
        try {
            val checking = async { EhApi(credentials, http).verifyLogin() }
            entered.await()
            credentials.save("B", "B-hash", "B-cookie")
            release.complete(Unit)
            checking.await()
            println("after_B_login_and_A_cookie member=${credentials.memberId}, igneous=${credentials.igneous}")
            assertEquals("B-cookie", credentials.igneous)
        } finally { http.close() }
    }
    @Test fun anAnonymousKeyCheckCannotUndoALaterAnonymousLogout() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = Tokens(null, null)
        val http = buildHttpClient(MockEngine {
            entered.complete(Unit); release.await()
            respond("""{"id":3,"username":"candidate","avatar_url":""}""")
        })
        try {
            val checking = async { runCatching { NhApi(tokens, http).loginWithApiKey("C") } }
            entered.await(); tokens.clear(); release.complete(Unit)
            kotlin.test.assertIs<com.atlanyuey.aenht.shared.nh.NhException.LoginChanged>(checking.await().exceptionOrNull())
            assertNull(tokens.accessToken)
        } finally { http.close() }
    }

    @Test fun aRefreshCannotUndoSwitchingAwayAndBackToTheSameToken() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokens = Tokens("A", "A-refresh")
        val http = buildHttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/refresh")) {
                entered.complete(Unit); release.await()
                respond("""{"access_token":"stale-A-renewed","refresh_token":"stale-refresh"}""")
            } else respond("{}", HttpStatusCode.Unauthorized)
        })
        try {
            val waiting = async { runCatching { NhApi(tokens, http).currentUser() } }
            entered.await()
            tokens.clear(); tokens.save("B", "B-refresh")
            tokens.clear(); tokens.save("A", "A-refresh")
            release.complete(Unit)
            kotlin.test.assertIs<com.atlanyuey.aenht.shared.nh.NhException.LoginChanged>(waiting.await().exceptionOrNull())
            assertEquals("A", tokens.accessToken)
            assertEquals("A-refresh", tokens.refreshToken)
        } finally { http.close() }
    }

    @Test fun aRateLimitedRequestIsNotReplayedAsADifferentLogin() = runTest {
        val tokens = Tokens("A", "A-refresh")
        var clock = 0L
        var calls = 0
        val limiter = com.atlanyuey.aenht.shared.net.RateLimiter(now = { clock })
        limiter.onWait = { _, milliseconds ->
            clock += milliseconds
            tokens.clear(); tokens.save("key:B", null)
        }
        val http = buildHttpClient(MockEngine {
            calls++
            respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        })
        try {
            val result = runCatching { NhApi(tokens, http, limiter).currentUser() }
            kotlin.test.assertIs<com.atlanyuey.aenht.shared.nh.NhException.LoginChanged>(result.exceptionOrNull())
            assertEquals(1, calls)
            assertEquals("key:B", tokens.accessToken)
        } finally { http.close() }
    }

}
