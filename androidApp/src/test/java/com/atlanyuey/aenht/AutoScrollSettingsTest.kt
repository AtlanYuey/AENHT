package com.atlanyuey.aenht

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.atlanyuey.aenht.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoScrollSettingsTest {
    @Test fun scrollingSpeedPersistsAndDoesNotReplacePagedInterval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(context)
        val previous = AppSettings.toJson()
        try {
            AppSettings.autoReadInterval = 2.5
            AppSettings.applyJson("""{"autoScrollSecondsPerScreen":8}""")
            val exported = AppSettings.toJson()
            AppSettings.init(context)
            assertEquals(8.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            assertEquals(2.5, AppSettings.autoReadInterval, 0.0)
            AppSettings.autoScrollSecondsPerScreen = 60.0
            AppSettings.applyJson(exported)
            assertEquals(8.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
        } finally { AppSettings.applyJson(previous) }
    }

    @Test fun oldExportsAndInvalidSyncedNumbersStayUsable() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Context>())
        val previous = AppSettings.toJson()
        try {
            AppSettings.autoScrollSecondsPerScreen = 20.0
            AppSettings.applyJson("""{"autoReadInterval":4}""")
            assertEquals(20.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.apply(mapOf("autoScrollSecondsPerScreen" to Double.NaN))
            assertEquals(15.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.applyJson("""{"autoScrollSecondsPerScreen":-999}""")
            assertEquals(5.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.applyJson("""{"autoScrollSecondsPerScreen":999}""")
            assertEquals(60.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
        } finally { AppSettings.applyJson(previous) }
    }
}
