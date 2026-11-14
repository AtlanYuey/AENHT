package com.atlanyuey.aenht.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App lock: with Settings › General › App Lock on, the app asks for the
 * fingerprint / face or the device's PIN when it opens or comes back.
 */
object AppLock {
    var locked by mutableStateOf(false)
        private set
    private var prompting = false
    const val REQUEST = 4711

    /** The device has a screen lock (otherwise there is nothing to ask for). */
    fun available(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** The app went to the background: it locks. */
    fun onBackground() {
        if (AppSettings.appLock) locked = true
    }

    fun onLaunch() {
        if (AppSettings.appLock) locked = true
    }

    /** Asks to unlock (biometrics or the device credential). */
    fun unlock(activity: Activity) {
        if (!locked || prompting) return
        if (!available(activity)) {
            locked = false
            return
        }
        prompting = true
        val title = L10n.string("Unlock AENHT")
        if (Build.VERSION.SDK_INT >= 28) {
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    prompting = false
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    prompting = false
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
            if (intent == null) {
                prompting = false
                locked = false
                return
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST)
        }
    }

    /** The answer of the device credential screen (Android 8). */
    fun onResult(ok: Boolean) {
        prompting = false
        if (ok) locked = false
    }
}
