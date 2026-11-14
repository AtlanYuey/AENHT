package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.app.Diagnostics
import com.atlanyuey.aenht.shared.app.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSupportTest {
    @Test
    fun redactsSecrets() {
        val r = Diagnostics.redact("Authorization: User abc.def Cookie: ipb_member_id=123; ipb_pass_hash=deadbeef {\"apikey\":\"xyz\"} sk-abcdefghijk key:secret123 me@example.com")
        assertFalse(r.contains("abc.def"))
        assertFalse(r.contains("deadbeef"))
        assertFalse(r.contains("123;"))
        assertFalse(r.contains("xyz"))
        assertFalse(r.contains("abcdefghijk"))
        assertFalse(r.contains("secret123"))
        assertFalse(r.contains("example.com"))
        Diagnostics.clear()
        Diagnostics.log("nh", "GET galleries → 503")
        assertEquals(1, Diagnostics.entries().size)
        assertTrue(Diagnostics.report("AENHT 0.1.0").contains("[nh] GET galleries → 503"))
    }

    @Test
    fun versions() {
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("dev-latest", "0.1.0"))
    }
}
