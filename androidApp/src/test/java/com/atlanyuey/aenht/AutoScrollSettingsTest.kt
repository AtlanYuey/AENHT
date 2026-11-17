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
    @Test fun continueOnDragDefaultsOffAndSurvivesExportAndRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(context)
        val previous = AppSettings.toJson()
        try {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("continueAutoScrollOnDrag").commit()
            AppSettings.init(context)
            org.junit.Assert.assertFalse(AppSettings.continueAutoScrollOnDrag)
            AppSettings.continueAutoScrollOnDrag = true
            val exported = AppSettings.toJson()
            AppSettings.init(context)
            org.junit.Assert.assertTrue(AppSettings.continueAutoScrollOnDrag)
            AppSettings.continueAutoScrollOnDrag = false
            AppSettings.applyJson(exported)
            org.junit.Assert.assertTrue(AppSettings.continueAutoScrollOnDrag)
        } finally { AppSettings.applyJson(previous) }
    }

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
            AppSettings.autoScrollSecondsPerScreen = 30.0
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
            assertEquals(10.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.applyJson("""{"autoScrollSecondsPerScreen":-999}""")
            assertEquals(1.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.applyJson("""{"autoScrollSecondsPerScreen":999}""")
            assertEquals(30.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
        } finally { AppSettings.applyJson(previous) }
    }

    @Test fun oldSlowSpeedIsClampedAndRetiredSpreadKeysDoNotOverrideContinuousGap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.init(context)
        val previous = AppSettings.toJson()
        try {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putFloat("autoScrollSecondsPerScreen", 60f).commit()
            AppSettings.init(context)
            assertEquals(30.0, AppSettings.autoScrollSecondsPerScreen, 0.0)
            AppSettings.applyJson("""{"spreadGapOn":false,"spreadGap":60,"pageGap":12}""")
            assertEquals(12.0, AppSettings.pageGap, 0.0)
            org.junit.Assert.assertFalse(AppSettings.snapshot().containsKey("spreadGap"))
            org.junit.Assert.assertFalse(AppSettings.snapshot().containsKey("spreadGapOn"))
        } finally { AppSettings.applyJson(previous) }
    }
}
