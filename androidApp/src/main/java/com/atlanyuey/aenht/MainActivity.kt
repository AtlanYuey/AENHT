package com.atlanyuey.aenht

import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.blur
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
        if (savedInstanceState == null) com.atlanyuey.aenht.core.AppLock.onLaunch()
        setContent {
            AppTheme {
                LaunchedEffect(Unit) { Session.reload() }
                LaunchedEffect(Unit) { TagStore.prepare() }
                androidx.compose.foundation.layout.Box(
                    if (com.atlanyuey.aenht.core.Privacy.blursApp) androidx.compose.ui.Modifier.blur(40.dp) else androidx.compose.ui.Modifier,
                ) {
                    RootView(linkedGallery)
                }
                if (com.atlanyuey.aenht.core.Privacy.covers) {
                    com.atlanyuey.aenht.ui.LockScreen(showsButton = false) {}
                }
                if (com.atlanyuey.aenht.core.AppLock.locked) {
                    com.atlanyuey.aenht.ui.LockScreen { com.atlanyuey.aenht.core.AppLock.unlock(this@MainActivity) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.atlanyuey.aenht.core.Privacy.hidden = false
        com.atlanyuey.aenht.core.AppLock.unlock(this)
        // With the lock on or "Cover the App", the app's content isn't shown in Recents.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(
                !com.atlanyuey.aenht.core.AppSettings.appLock && com.atlanyuey.aenht.core.Privacy.mode != com.atlanyuey.aenht.core.BackgroundPrivacy.Cover,
            )
        }
    }

    override fun onPause() {
        // Hidden before the system takes the app switcher's picture.
        if (com.atlanyuey.aenht.core.AppSettings.backgroundPrivacy != com.atlanyuey.aenht.core.BackgroundPrivacy.Off) {
            com.atlanyuey.aenht.core.Privacy.hidden = true
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) com.atlanyuey.aenht.core.AppLock.onBackground()
    }

    @Deprecated("Device credential screen of Android 8")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.atlanyuey.aenht.core.AppLock.REQUEST) com.atlanyuey.aenht.core.AppLock.onResult(resultCode == RESULT_OK)
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
