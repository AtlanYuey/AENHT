package com.atlanyuey.aenht

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.atlanyuey.aenht.core.AppLanguage
import com.atlanyuey.aenht.core.Session
import com.atlanyuey.aenht.core.TagStore
import com.atlanyuey.aenht.ui.RootView
import com.atlanyuey.aenht.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    /** A gallery link (either site) the app was opened with. */
    val linkedGallery = mutableStateOf<com.atlanyuey.aenht.shared.site.GalleryLink?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        handle(intent)
        setContent {
            AppTheme {
                LaunchedEffect(Unit) { Session.reload() }
                LaunchedEffect(Unit) { TagStore.prepare() }
                RootView(linkedGallery)
            }
        }
    }

    /** The volume keys turn pages in the reader (when that setting is on). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val handler = com.atlanyuey.aenht.screens.ReaderKeys.handler
        if (handler != null && com.atlanyuey.aenht.core.AppSettings.volumeKeyTurns) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { handler(true); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { handler(false); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.atlanyuey.aenht.screens.ReaderKeys.handler != null && com.atlanyuey.aenht.core.AppSettings.volumeKeyTurns &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        com.atlanyuey.aenht.shared.site.SiteLinks.find(url)?.let { linkedGallery.value = it }
    }
}
