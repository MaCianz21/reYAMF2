package com.mja.reyamf.xposed.services

import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import com.mja.reyamf.common.reflect.invokeMethodAs

/**
 * Sends the navigation bar's back, home and recents buttons to the floating window the user
 * interacted with last.
 *
 * The buttons inject their key events through InputManagerService, so its injection entry point
 * is the one place that sees every navigation bar flavour (SystemUI's three buttons and the
 * taskbar's copy) at once. The app inside a window runs on its own virtual display, which the
 * system's own handling of these keys never reaches: HOME and APP_SWITCH stay bound to the
 * default display, and BACK only leaves the window's app alone while the window is on screen.
 */
object NavKeyRouter {
    /** Set while a press we handled is still down, so its release is swallowed too. */
    @Volatile
    private var consumedDown = false

    /** Returns true when the event was handled and must not reach the system. */
    fun onInjectedEvent(event: InputEvent?): Boolean {
        val key = event as? KeyEvent ?: return false
        // Keys that already target a window's display are our own re-injection; let them pass.
        // getDisplayId() is a hidden API, hence the reflective read.
        val displayId = runCatching { key.invokeMethodAs<Int>("getDisplayId") }
            .getOrDefault(Display.DEFAULT_DISPLAY)
        if (displayId != Display.DEFAULT_DISPLAY) return false
        if (!isNavigationKey(key.keyCode)) return false

        if (key.action != KeyEvent.ACTION_DOWN) {
            val wasHandled = consumedDown
            consumedDown = false
            return wasHandled
        }

        val window = YAMFManager.activeWindow()?.takeIf { it.canReceiveKeys } ?: return false
        consumedDown = true
        when (key.keyCode) {
            KeyEvent.KEYCODE_BACK -> window.forwardKey(KeyEvent.KEYCODE_BACK)
            KeyEvent.KEYCODE_HOME -> {
                window.navHome()
                YAMFManager.clearActiveWindow()
            }

            KeyEvent.KEYCODE_APP_SWITCH -> YAMFManager.openAppList()
        }
        return true
    }

    private fun isNavigationKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> true
        else -> false
    }
}