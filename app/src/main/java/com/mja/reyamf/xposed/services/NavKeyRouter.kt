package com.mja.reyamf.xposed.services

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 *
 * The taskbar's back button is the exception: it asks the system to navigate back instead of
 * pressing a key, so it is taken from that call first in [onBackPressed] and only its fallback
 * key reaches the entry point below.
 */
object NavKeyRouter {
    private const val TAG = "reYAMF_NavKey"

    /** How long after the taskbar's back call the window receives the key it stands for. */
    private const val BACK_KEY_DELAY_MS = 150L

    /** How long a back key the taskbar presses itself is dropped after its back call. */
    private const val BACK_KEY_SWALLOW_MS = 600L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Until this time, a back key pressed by the taskbar itself is dropped: it was handled. */
    @Volatile
    private var swallowBackUntil = 0L

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
        // The taskbar can press the back key by itself right after its back call was claimed;
        // that copy is dropped so the app inside the window goes back once.
        if (key.keyCode == KeyEvent.KEYCODE_BACK && key.action == KeyEvent.ACTION_DOWN &&
            SystemClock.uptimeMillis() < swallowBackUntil
        ) {
            log(TAG, "dropped the taskbar's own back key")
            consumedDown = true
            return true
        }
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

    /**
     * The tablet taskbar's back button, taken before the system navigates back.
     *
     * That button does not press a key: it asks the system to navigate back, and the system hands
     * the press to the top task of the default display. A floating window is drawn there but its
     * app runs on another display, so on its own the press navigates the app behind the window.
     * While the user is working in a window, the press is answered here the same way an injected
     * back key is: the tablet keyboard goes first, then the app inside the window.
     *
     * @return true when the press was handled and the system must not navigate back itself.
     */
    fun onBackPressed(): Boolean {
        val window = YAMFManager.windowForBackPress() ?: return false
        log(TAG, "taskbar back goes to window ${window.displayId}")
        // The press is already claimed, so a key the taskbar presses itself once this call is
        // claimed is the same press and must not navigate a second time.
        swallowBackUntil = SystemClock.uptimeMillis() + BACK_KEY_SWALLOW_MS
        if (ImeVisibilityBridge.hideTabletKeyboard()) return true
        // The taskbar's own back handling is still running while this call is made and a key
        // injected into it is dropped, so the window is told just after.
        mainHandler.postDelayed({
            runCatching { window.forwardKey(KeyEvent.KEYCODE_BACK) }
                .onFailure { log(TAG, "cannot navigate back in window ${window.displayId}", it) }
        }, BACK_KEY_DELAY_MS)
        return true
    }

    private fun isNavigationKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> true
        else -> false
    }
}
