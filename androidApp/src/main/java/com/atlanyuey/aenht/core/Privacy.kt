package com.atlanyuey.aenht.core

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    val blursContent get() = hidden && mode == BackgroundPrivacy.BlurContent
    val blursImages get() = hidden && mode == BackgroundPrivacy.BlurImages
}
