package com.atlanyuey.aenht

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.atlanyuey.aenht.core.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainSettingsImportTest {
    @Test fun importedBoundaryValuesLeaveUsableConcurrencyAndAtLeastOneSite() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Context>())
        val previous = AppSettings.toJson()
        try {
            AppSettings.applyJson("""{"downloadPages":-99,"downloadGalleries":0,"downloadRetries":-10,"preloadPages":-99,"nhentaiEnabled":false,"ehentaiEnabled":false}""")
            assertTrue(AppSettings.downloadPages >= 1)
            assertTrue(AppSettings.downloadGalleries >= 1)
            assertTrue(AppSettings.downloadRetries >= 0)
            assertTrue(AppSettings.preloadPages >= 1)
            assertTrue(AppSettings.enabledSites.isNotEmpty())
            assertTrue(AppSettings.currentSite in AppSettings.enabledSites)
        } finally { AppSettings.applyJson(previous) }
    }
}
