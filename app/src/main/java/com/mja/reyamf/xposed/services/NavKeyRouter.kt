package com.mja.reyamf.xposed.services

import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import com.mja.reyamf.common.reflect.invokeMethodAs
import com.mja.reyamf.xposed.utils.log

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
    private const val TAG = "reYAMF_NavKey"

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
        // The navigation bar's injected keys carry either -1 (display id unset) or 0 (the default
        // display); only our own re-injections carry a window's virtual display id (> 0). Filtering
        // on "== 0" dropped every real press, so anything above the default display is our echo.
        if (displayId > Display.DEFAULT_DISPLAY) return false
        if (!isNavigationKey(key.keyCode)) return false
        if (key.action == KeyEvent.ACTION_DOWN) {
            log(TAG, "nav ${key.keyCode} injected on display $displayId")
        }

        if (key.action != KeyEvent.ACTION_DOWN) {
            val wasHandled = consumedDown
            consumedDown = false
            return wasHandled
        }

        val window = YAMFManager.activeWindow()
        if (window == null || !window.canReceiveKeys) {
            log(TAG, "nav ${key.keyCode} on display $displayId ignored: no window ready for keys")
            return false
        }
        log(TAG, "nav ${key.keyCode} on display $displayId -> window ${window.displayId}")
        consumedDown = true
        // The press is already claimed, so a failing action must not let it fall through to the
        // display behind the window (and must not skip the matching release).
        runCatching {
            when (key.keyCode) {
                // A kept tablet keyboard is on the default display, out of the back key's
                // reach: the first press closes it, the next one leaves the app.
                KeyEvent.KEYCODE_BACK ->
                    if (!ImeVisibilityBridge.hideTabletKeyboard()) {
                        window.forwardKey(KeyEvent.KEYCODE_BACK)
                    }
                KeyEvent.KEYCODE_HOME -> {
                    window.navHome()
                    YAMFManager.clearActiveWindow()
                }

                KeyEvent.KEYCODE_APP_SWITCH -> YAMFManager.openAppList()
            }
        }.onFailure {
            log(TAG, "cannot act on nav ${key.keyCode} for window ${window.displayId}", it)
        }
        return true
    }

    private fun isNavigationKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> true
        else -> false
    }
}
