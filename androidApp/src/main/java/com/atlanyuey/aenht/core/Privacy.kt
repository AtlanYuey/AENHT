package com.atlanyuey.aenht.core

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/**
 * Hides the app's content while it is in the background (Settings ›
 * General › In the Background), so the app switcher shows nothing private.
 */
object Privacy {
    /** The app is in the background (or going there). */
    var hidden by mutableStateOf(false)

    /** The mode in effect; blurring needs Android 12, before that the app is covered. */
    val mode: BackgroundPrivacy
        get() {
            val m = AppSettings.backgroundPrivacy
            return if (m != BackgroundPrivacy.Off && m != BackgroundPrivacy.Cover && Build.VERSION.SDK_INT < 31) BackgroundPrivacy.Cover else m
        }

    val blursApp get() = hidden && mode == BackgroundPrivacy.BlurApp
    val covers get() = hidden && mode == BackgroundPrivacy.Cover
    /** Content text (titles, tags, comments) is blurred; buttons, bars and settings aren't. */
    val blursText get() = hidden && mode == BackgroundPrivacy.BlurContent
    val blursImages get() = hidden && (mode == BackgroundPrivacy.BlurImages || mode == BackgroundPrivacy.BlurContent)
}

/** Blurs content text in the background (Blur Pictures and Text). */
fun Modifier.privacyText(): Modifier = if (Privacy.blursText) this.blur(7.dp) else this
