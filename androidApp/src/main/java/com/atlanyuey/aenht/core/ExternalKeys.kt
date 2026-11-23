package com.atlanyuey.aenht.core

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The keys of a keyboard or a Bluetooth page turner that turn pages. */
enum class PageKey {
    /** Down, Page Down, Space, Media Next: the next page whatever the direction. */
    Next,
    /** Up, Page Up, Shift-Space, Media Previous: the previous page. */
    Previous,
    /** The arrows left and right: the page on that side (by the reading direction). */
    Right, Left;

    companion object {
        /** The page key [event] is, if it is one. */
        fun of(event: KeyEvent): PageKey? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> Next
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Previous
            KeyEvent.KEYCODE_SPACE -> if (event.isShiftPressed) Previous else Next
            KeyEvent.KEYCODE_DPAD_RIGHT -> Right
            KeyEvent.KEYCODE_DPAD_LEFT -> Left
            else -> null
        }

        /**
         * Whether [key] turns forward, reading [rightToLeft]; [swap] (Swap Next
         * and Previous) for page turners whose buttons are the other way round.
         */
        fun forward(key: PageKey, rightToLeft: Boolean, swap: Boolean): Boolean {
            val forward = when (key) {
                Next -> true
                Previous -> false
                Right -> !rightToLeft
                Left -> rightToLeft
            }
            return if (swap) !forward else forward
        }
    }
}

/**
 * Hardware keyboards and Bluetooth page turners (keyboards to the system)
 * connected now: the reader's Controls show their settings only then.
 */
object ExternalKeys {
    /** The connected ones' names; empty without one. */
    var names by mutableStateOf<List<String>>(emptyList())
        private set

    private var manager: InputManager? = null
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    fun start(context: Context) {
        if (manager != null) return
        val inputs = context.getSystemService(InputManager::class.java) ?: return
        manager = inputs
        inputs.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun refresh() {
        val inputs = manager ?: return
        names = inputs.inputDeviceIds.toList().mapNotNull { inputs.getInputDevice(it) }
            .filter { device ->
                device.isExternal && !device.isVirtual &&
                    device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                    // Headphones' play / pause buttons come as a keyboard too.
                    !device.name.contains("AVRCP", ignoreCase = true)
            }
            .map { it.name.ifBlank { L10n.string("Keyboard") } }
            .distinct()
    }
}
