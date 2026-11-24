package com.mja.reyamf.common

import android.os.Build

/**
 * Whether DISPLAY_IME_POLICY_FALLBACK_DISPLAY can keep the keyboard up for a window's app.
 *
 * The fallback policy draws the IME on the tablet's own display while the app that asked for it
 * runs on the window's virtual display. Up to Android 15 the window manager also created an IME
 * insets source on the app's display, so the app's show request reached that keyboard and it
 * stayed visible. From Android 16 the request is routed through the app display's own insets
 * source (ImeInsetsSourceProvider reports it to InputMethodManagerService), and the fallback
 * policy does not create one there: the request is reported back as withdrawn and the keyboard is
 * hidden again as soon as it is drawn (ImeTracker: onRequestHide with
 * IME_REQUESTED_CHANGED_LISTENER). The window would be left with no keyboard at all.
 *
 * On those releases a window always hosts the keyboard on its own display. The setting is kept
 * because releases that can show the tablet's keyboard still get it.
 */
object ImePolicy {
    /** First release whose IME request path the fallback policy cannot satisfy. */
    private const val FIRST_RELEASE_WITHOUT_FALLBACK_IME = 36

    /** True when the window's app can type on the tablet's own, full-size keyboard. */
    val fallbackSupported: Boolean
        get() = Build.VERSION.SDK_INT < FIRST_RELEASE_WITHOUT_FALLBACK_IME
}
