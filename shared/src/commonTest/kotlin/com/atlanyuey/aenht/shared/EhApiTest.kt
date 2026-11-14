package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.eh.EhAdvancedSearch
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.eh.EhCredentialStorage
import com.atlanyuey.aenht.shared.eh.EhException
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhToplistPeriod
import com.atlanyuey.aenht.shared.net.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class MemoryEhCredentials(
    override var memberId: String? = null,
    override var passHash: String? = null,
    override var igneous: String? = null,
) : EhCredentialStorage {
    override fun save(memberId: String, passHash: String, igneous: String?) {
        this.memberId = memberId
        this.passHash = passHash
        this.igneous = igneous
    }

    override fun clear() {
        memberId = null
        passHash = null
        igneous = null
    }
}

class EhApiTest {
    private val html = headersOf(HttpHeaders.ContentType, "text/html; charset=UTF-8")
    private fun list(mode: String) = """<table class="itg $mode"><tr><td class="gl1c glcat"><div class="cn">Manga</div></td>
        <td class="gl3c glname"><a href="https://e-hentai.org/g/7/abcdef0123/"><div class="glink">T</div></a></td></tr></table>
        <a id="unext" href="https://e-hentai.org/?next=7">Next</a>"""

    @Test
    fun buildsSearchUrls() {
        val api = EhApi(MemoryEhCredentials(), buildHttpClient(MockEngine { respond("") }))
        val url = api.listUrl(
            EhListSource.Search(
                "female:glasses$", setOf(EhCategory.MANGA),
                EhAdvancedSearch(minRating = 4, minPages = 10, maxPages = 50),
            ),
            cursor = "123",
        )
        assertTrue(url.startsWith("https://e-hentai.org/?f_search=female%3Aglasses%24"), url)
        assertTrue("f_cats=${1023 - 4}" in url, url)
        assertTrue("advsearch=1" in url && "f_srdd=4" in url && "f_spf=10" in url && "f_spt=50" in url, url)
        assertTrue(url.endsWith("next=123"), url)
        assertEquals("https://e-hentai.org/toplist.php?tl=11&p=2", api.listUrl(EhListSource.Toplist(EhToplistPeriod.ALL_TIME), "2"))
        assertEquals("https://e-hentai.org/favorites.php?favcat=3", api.listUrl(EhListSource.Favorites(3)))
    }

    @Test
    fun switchesToCompactAndSendsCookies() = runTest {
        val creds = MemoryEhCredentials("42", "hash")
        val seen = mutableListOf<String>()
        var compact = false
        val engine = MockEngine { request ->
            seen += request.url.toString()
            val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
            assertTrue("ipb_member_id=42" in cookie && "ipb_pass_hash=hash" in cookie && "nw=1" in cookie, cookie)
            if ("inline_set=dm_l" in request.url.toString()) compact = true
            respond(list(if (compact) "gltc" else "glte"), HttpStatusCode.OK, html)
        }
        val api = EhApi(creds, buildHttpClient(engine))
        val page = api.list(EhListSource.Search())
        assertEquals(1, page.galleries.size)
        assertEquals(EhCategory.MANGA, page.galleries[0].category)
        assertEquals(3, seen.size) // extended list, switch, compact list
    }

    @Test
    fun picksUpExHentaiAccessAndUsesIt() = runTest {
        val creds = MemoryEhCredentials("42", "hash")
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://e-hentai.org/home.php") -> respond("<div class=\"homebox\">Image Limits</div>", HttpStatusCode.OK, html)
                url.startsWith("https://exhentai.org/") && "igneous=" !in request.headers[HttpHeaders.Cookie].orEmpty() ->
                    respond("", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, listOf("igneous=abc123; path=/; domain=.exhentai.org")))
                else -> respond(list("gltc"), HttpStatusCode.OK, html)
            }
        }
        val api = EhApi(creds, buildHttpClient(engine))
        assertTrue(api.verifyLogin())
        assertEquals("abc123", creds.igneous)
        assertTrue(api.hasExHentaiAccess)
        api.useExHentai = true
        assertEquals("https://exhentai.org", api.host)
        assertEquals(1, api.list(EhListSource.Search()).galleries.size)
    }

    @Test
    fun reportsBansAndNoAccess() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "exhentai.org") respond("", HttpStatusCode.OK, html)
            else respond("Your IP address has been temporarily banned for excessive pageloads.", HttpStatusCode.OK, html)
        }
        val api = EhApi(MemoryEhCredentials("1", "h"), buildHttpClient(engine))
        assertFailsWith<EhException.Banned> { api.list(EhListSource.Popular) }
        api.useExHentai = true
        assertFailsWith<EhException.NoAccess> { api.list(EhListSource.Popular) }
    }

    @Test
    fun favoritesNeedALoginAndPostTheForm() = runTest {
        var form: String? = null
        val engine = MockEngine { request ->
            form = ((request.body as? FormDataContent)?.formData)?.let { p -> p.names().sorted().joinToString("&") { "$it=${p[it]}" } }
            respond("<html>ok</html>", HttpStatusCode.OK, html)
        }
        assertFailsWith<EhException.NotLoggedIn> {
            EhApi(MemoryEhCredentials(), buildHttpClient(engine)).setFavorite(1, "t", 2)
        }
        val api = EhApi(MemoryEhCredentials("1", "h"), buildHttpClient(engine))
        api.setFavorite(1, "t", 2, "note")
        assertEquals("apply=Add to Favorites&favcat=2&favnote=note&update=1", form)
        api.setFavorite(1, "t", null)
        assertTrue(form!!.contains("favcat=favdel"))
    }
}
