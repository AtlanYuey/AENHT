package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.eh.EhCookieText
import com.atlanyuey.aenht.shared.eh.EhCookies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EhCookieTextTest {
    private val hash = "0123456789abcdef0123456789abcdef"
    private val full = EhCookies("1234567", hash, "a1b2c3d4e5f6")

    @Test fun aCookieHeader() {
        assertEquals(full, EhCookieText.find("ipb_member_id=1234567; ipb_pass_hash=$hash; igneous=a1b2c3d4e5f6; sk=xyz"))
        assertEquals(full, EhCookieText.find("Cookie: sl=dm_2; ipb_member_id=1234567;ipb_pass_hash=$hash; igneous=a1b2c3d4e5f6"))
    }

    @Test fun aCookieExtensionsJsonExport() {
        val json = """
            [{"domain":".e-hentai.org","name":"ipb_member_id","path":"/","value":"1234567"},
             {"value":"$hash","name":"ipb_pass_hash","domain":".e-hentai.org"},
             {"name":"igneous","value":"a1b2c3d4e5f6","domain":".exhentai.org"},
             {"name":"sk","value":"zz"}]
        """.trimIndent()
        assertEquals(full, EhCookieText.find(json))
    }

    @Test fun aCookiesTxtFile() {
        val txt = """
            # Netscape HTTP Cookie File
            .e-hentai.org	TRUE	/	FALSE	1893456000	ipb_member_id	1234567
            .e-hentai.org	TRUE	/	FALSE	1893456000	ipb_pass_hash	$hash
            .exhentai.org	TRUE	/	FALSE	1893456000	igneous	a1b2c3d4e5f6
        """.trimIndent()
        assertEquals(full, EhCookieText.find(txt))
    }

    @Test fun rowsFromTheDeveloperToolsAndNameValueLines() {
        assertEquals(full.copy(igneous = null), EhCookieText.find("ipb_member_id\t1234567\t.e-hentai.org\t/\nipb_pass_hash\t$hash\t.e-hentai.org\t/"))
        assertEquals(full, EhCookieText.find("ipb_member_id: 1234567\nipb_pass_hash: $hash\nigneous: a1b2c3d4e5f6"))
        assertEquals(full.copy(igneous = null), EhCookieText.find("ipb_member_id = \"1234567\"\nipb_pass_hash = '$hash'"))
    }

    @Test fun valuesThatCantBeTheCookiesAreLeftOut() {
        // No access to ExHentai: igneous is "mystery".
        assertEquals(full.copy(igneous = null), EhCookieText.find("ipb_member_id=1234567; ipb_pass_hash=$hash; igneous=mystery"))
        // Logged out: the member id is 0, the hash isn't one.
        assertNull(EhCookieText.find("ipb_member_id=0; ipb_pass_hash=nothing"))
        assertNull(EhCookieText.find("https://e-hentai.org/g/123/abcdef1234/"))
        assertNull(EhCookieText.find("just some text"))
        // Another cookie whose name ends the same isn't taken.
        assertNull(EhCookieText.find("xipb_member_id=1234567"))
    }

    @Test fun onlySomeOfThem() {
        assertEquals(EhCookies("1234567", null, null), EhCookieText.find("ipb_member_id=1234567"))
    }
}
