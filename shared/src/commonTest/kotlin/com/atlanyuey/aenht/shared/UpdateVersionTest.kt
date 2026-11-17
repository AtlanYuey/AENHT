package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.app.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionTest {
    @Test fun stableReleaseIsNewerThanItsBeta() {
        assertTrue(UpdateChecker.isNewer("v0.2.2", "0.2.2-beta.1"))
        assertFalse(UpdateChecker.isNewer("v0.2.2-beta.1", "0.2.2"))
    }

    @Test fun coreVersionWinsBeforePrereleaseIdentifiers() {
        assertFalse(UpdateChecker.isNewer("v0.2.1", "0.2.2-beta.1"))
        assertTrue(UpdateChecker.isNewer("v0.2.2-beta.1", "0.2.1"))
        assertFalse(UpdateChecker.isNewer("v0.2.2", "0.2.2"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2"))
    }

    @Test fun prereleasesUseSemverOrdering() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        for ((a, b) in versions.zipWithNext()) {
            assertTrue(UpdateChecker.isNewer(b, a), "$b > $a")
            assertFalse(UpdateChecker.isNewer(a, b), "$a < $b")
        }
    }

    @Test fun buildMetadataDoesNotChangePrecedence() {
        assertFalse(UpdateChecker.isNewer("1.2.3+4", "1.2.3+3"))
        assertFalse(UpdateChecker.isNewer("1.2.3-beta.1+5", "1.2.3-beta.1+4"))
    }

    @Test fun largeNumericPrereleaseIdentifiersDoNotOverflow() {
        assertTrue(UpdateChecker.isNewer("1.2.3-beta.10000000000000000000000000", "1.2.3-beta.9999999999999999999999999"))
    }

    @Test fun malformedOrRollingVersionNamesDoNotSuggestAnUpdate() {
        for (bad in listOf("dev-latest", "", "0.2.2-", "x0.2.2", "0.2.bad", "1.2.3.4")) {
            assertFalse(UpdateChecker.isNewer(bad, "0.2.1"))
            assertFalse(UpdateChecker.isNewer("0.2.2", bad))
        }
    }
}
