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
        com.atlanyuey.aenht.core.ExternalKeys.start(this)
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

    /**
     * A keyboard's or page turner's keys turn the pages in the reader (Keys
     * Turn Pages): taken before the views, which would move the focus with them.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // A stylus's Bluetooth buttons (Android 14 and later): what Controls › Stylus says.
        com.atlanyuey.aenht.core.StylusButton.ofKey(event.keyCode)?.let { button ->
            com.atlanyuey.aenht.core.AppSettings.stylusSeen = true
            val act = com.atlanyuey.aenht.screens.ReaderKeys.stylus
            if (act != null) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) act(button)
                return true
            }
        }
        val turn = com.atlanyuey.aenht.screens.ReaderKeys.deviceTurn
        val key = com.atlanyuey.aenht.core.PageKey.of(event)
        if (turn != null && key != null && com.atlanyuey.aenht.core.AppSettings.pageKeys) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val rtl = com.atlanyuey.aenht.core.AppSettings.readMode == com.atlanyuey.aenht.core.ReadMode.RightToLeft
                turn(com.atlanyuey.aenht.core.PageKey.forward(key, rtl, com.atlanyuey.aenht.core.AppSettings.pageKeysSwap))
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** A stylus touching the screen: its settings show (Controls › Stylus). */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        noteStylus(event)
        return super.dispatchTouchEvent(event)
    }

    /**
     * A stylus hovering: its settings show; a barrel button pressed while it
     * hovers is the reader's (what Controls › Stylus says).
     */
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val stylus = noteStylus(event)
        val act = com.atlanyuey.aenht.screens.ReaderKeys.stylus
        if (stylus && act != null && event.actionMasked == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
            com.atlanyuey.aenht.core.StylusButton.ofMotionButton(event.actionButton)?.let { act(it); return true }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Whether [event] is a stylus's (remembered once: its settings show from then on). */
    private fun noteStylus(event: android.view.MotionEvent): Boolean {
        val tool = event.getToolType(0)
        val stylus = tool == android.view.MotionEvent.TOOL_TYPE_STYLUS || tool == android.view.MotionEvent.TOOL_TYPE_ERASER
        if (stylus && !com.atlanyuey.aenht.core.AppSettings.stylusSeen) com.atlanyuey.aenht.core.AppSettings.stylusSeen = true
        return stylus
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
