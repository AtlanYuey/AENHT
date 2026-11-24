package com.atlanyuey.aenht

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atlanyuey.aenht.shared.site.GalleryVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's regexes on a real Android: its regex engine is ICU, stricter
 * than the desktop JVM's the other tests run on (a "}" with no "{" before
 * it compiles there, and crashes here).
 */
@RunWith(AndroidJUnit4::class)
class DeviceRegexTest {
    /** Every class that holds regexes, initialized (its regexes compiled) on this Android. */
    @Test fun everyRegexHolderInitializes() {
        val failures = mutableListOf<String>()
        for (name in HOLDERS) {
            try {
                Class.forName(name, true, javaClass.classLoader)
            } catch (_: ClassNotFoundException) {
                // A file without top-level declarations has no class of its own.
            } catch (e: Throwable) {
                failures += "$name: ${e.cause ?: e}"
            }
        }
        assertTrue("Regexes that don't compile on Android:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The gallery page's other versions (the crash in issue #12). */
    @Test fun galleryVersionsWorkOnAndroid() {
        assertEquals("Title", GalleryVersions.core("(C104) [Circle] Title {Group} (Parody) [Chinese]"))
        assertTrue(GalleryVersions.isVersion(listOf("[A] Title [English]"), "[B] Title [Chinese]"))
    }

    private companion object {
        val HOLDERS = listOf(
            "com.atlanyuey.aenht.core.AppLanguage", "com.atlanyuey.aenht.core.Downloads",
            "com.atlanyuey.aenht.core.HistoryStore", "com.atlanyuey.aenht.core.L10n", "com.atlanyuey.aenht.core.L10nKt",
            "com.atlanyuey.aenht.core.StoresKt", "com.atlanyuey.aenht.core.TagStore",
            "com.atlanyuey.aenht.screens.EhFolders", "com.atlanyuey.aenht.screens.EhGalleryKt",
            "com.atlanyuey.aenht.shared.app.Diagnostics", "com.atlanyuey.aenht.shared.app.DiagnosticsKt",
            "com.atlanyuey.aenht.shared.eh.EhApiKt", "com.atlanyuey.aenht.shared.eh.EhParser", "com.atlanyuey.aenht.shared.eh.EhParserKt",
            "com.atlanyuey.aenht.shared.net.NetFailure", "com.atlanyuey.aenht.shared.net.NetFailureKt",
            "com.atlanyuey.aenht.shared.nh.NHHost", "com.atlanyuey.aenht.shared.nh.NhModelsKt",
            "com.atlanyuey.aenht.shared.site.ContentFilterKt", "com.atlanyuey.aenht.shared.site.EhSimilar",
            "com.atlanyuey.aenht.shared.site.GalleryVersions", "com.atlanyuey.aenht.shared.site.QueryConverter",
            "com.atlanyuey.aenht.shared.site.QueryConverterKt", "com.atlanyuey.aenht.shared.site.SiteLinks",
            "com.atlanyuey.aenht.shared.site.SiteLinksKt", "com.atlanyuey.aenht.shared.site.VersionsKt",
        )
    }
}
