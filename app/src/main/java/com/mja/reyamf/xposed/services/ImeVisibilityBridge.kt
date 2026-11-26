package com.mja.reyamf.xposed.services

import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import com.mja.reyamf.common.reflect.findFieldOrNull
import com.mja.reyamf.common.reflect.findMethodOrNull
import com.mja.reyamf.common.reflect.invokeMethod
import com.mja.reyamf.common.reflect.getObject
import com.mja.reyamf.common.reflect.getObjectAs
import com.mja.reyamf.common.reflect.invokeMethodAs
import com.mja.reyamf.xposed.utils.Instances

/**
 * Keeps the tablet's own, full-size keyboard up for an app running in a floating window.
 *
 * With DISPLAY_IME_POLICY_FALLBACK_DISPLAY the keyboard is drawn on the tablet's display while the
 * app that asked for it runs on the window's display. From Android 16 the framework reads that
 * request back through the *app display's* IME insets source, which a fallback display does not
 * have: the request counts as withdrawn right after the keyboard is drawn, the app is told the
 * keyboard is gone, and the keyboard is hidden again.
 *
 * While an app on one of our displays wants the keyboard, this bridge keeps that request true and
 * ignores the change listener and the server-side hide that follow from the withdrawal. The back
 * press closes the keyboard through [hideTabletKeyboard], because the keyboard is drawn on a
 * display the key never reaches.
 */
object ImeVisibilityBridge {
    private const val TAG = "reYAMF_ImeBridge"

    /** The keyboard's bit in the insets types, which the window manager numbers the same way. */
    private const val IME_TYPE = 8

    /** Entry points that ask for the keyboard, shared by both hook flavours. */
    val SHOW_METHODS: Set<String> = setOf(
        "showSoftInput",
        "showCurrentInputInternal",
        "showCurrentInputLocked",
    )

    /** Entry points that take the keyboard back. */
    val HIDE_METHODS: Set<String> = setOf(
        "hideSoftInput",
        "hideCurrentInputInternal",
    )

    /** The hide entry point the app itself calls; every other one is the server's reaction. */
    const val CLIENT_HIDE = "hideSoftInput"

    /**
     * True while the user closed the keyboard and nothing has asked for it again.
     *
     * The keyboard is drawn on the tablet display, which keeps asking for it on its own as long
     * as the editable field has focus ([SHOW_AUTO_EDITOR_FORWARD_NAV], [CONTROLS_CHANGED]). Those
     * requests carry no window token, while a request driven by the app's own window does, so the
     * two can be told apart: after the user closed the keyboard, only a window may bring it back.
     */
    @Volatile
    private var userClosed = false

    /**
     * True while a tap may bring the keyboard back, so the window only has to look at a touch
     * then. Reading this is a single volatile field read, which is what every touch costs while the
     * keyboard is up.
     */
    val waitsForTap: Boolean
        get() = userClosed

    /** Display whose app asked for the tablet keyboard, or -1 when none did. */
    @Volatile
    private var keptDisplayId = -1

    /** Window that asked for the keyboard, so it can be told to hide it again. */
    @Volatile
    private var keptToken: IBinder? = null

    /** Lookups the show path needs on every request, resolved once. */
    @Volatile
    private var windowMapField: java.lang.reflect.Field? = null
    @Volatile
    private var inputShownField: java.lang.reflect.Field? = null
    @Volatile
    private var windowDisplayIdMethod: java.lang.reflect.Method? = null
    @Volatile
    private var lastToken: IBinder? = null
    @Volatile
    private var lastTokenDisplayId = -1

    /** Window whose show request last went through, so its repeats can be told apart. */
    @Volatile
    private var lastRequestTarget: Any? = null

    /** The input method service and the entry points that hide the keyboard of a window. */
    @Volatile
    private var immService: Any? = null
    @Volatile
    private var hideMethods: List<java.lang.reflect.Method> = emptyList()
    @Volatile
    private var showMethods: List<java.lang.reflect.Method> = emptyList()

    /**
     * True while the tablet keyboard must stay requested for a window's display. Only the fallback
     * policy needs the help; with the in-window keyboard the framework does the right thing alone.
     */
    val keepsTabletKeyboard: Boolean
        get() = keptDisplayId > 0 &&
                !YAMFManager.config.showImeInWindow &&
                YAMFManager.hostsWindow(keptDisplayId)

    /**
     * True when a show request must be dropped: the tablet display asking for the keyboard again
     * on its own after the user closed it. Those requests carry no window token; only a window can
     * bring the keyboard back.
     */
    fun dropsShowRequest(token: IBinder?): Boolean = token == null && userClosed

    /**
     * True when the keyboard is already drawn for the window [token] belongs to, so the app asking
     * again needs no work.
     *
     * Apps ask for the keyboard on every touch of a served field. With the fallback policy each of
     * those requests is a real show here, and each one makes the window manager animate and place
     * the keyboard again: a relayout and a dozen redraws of the window per touch, for a keyboard
     * that is already up. Nothing changes on screen, so the request is answered without work.
     *
     * Only the window that holds the keyboard may answer "already shown". The keyboard is drawn
     * once, for the tablet display, but which window it serves follows the keep: a request from
     * another window has to go through, or the keep stays on the window the user left. A request
     * without a window token is the tablet display re-asking on its own, which the keep answers.
     */
    fun alreadyShown(service: Any?, token: IBinder?): Boolean {
        if (userClosed || !keepsTabletKeyboard) return false
        if (token != null && displayOf(token) != keptDisplayId) return false
        return inputShown(service)
    }

    /**
     * True when a request to *show* the keyboard repeats one that is already in force.
     *
     * Apps report a request on every touch of a served field. On a fallback display each of those
     * reports would run the whole insets/IME policy again, which re-animates and re-places a
     * keyboard that is already drawn: tens of window relayouts and redraws per touch for no
     * visible change. A repeat of a request that already holds needs no work at all.
     *
     * The repeat is recognised by its target: one window repeats the same call, while the first
     * call of another window must reach the framework and move the keep to its display.
     */
    fun repeatsActiveRequest(requested: Boolean, target: Any?): Boolean {
        if (!requested || !keepsTabletKeyboard) return false
        if (target == null || target !== lastRequestTarget) return false
        return inputShown(immService)
    }

    /** True when the input method service reports the keyboard as drawn. */
    private fun inputShown(service: Any?): Boolean = runCatching {
        val field = inputShownField ?: service?.javaClass
            ?.findFieldOrNull("mInputShown")
            ?.also { inputShownField = it }
        field?.get(service) == true
    }.getOrDefault(false)

    /** An app asked for the keyboard: remember its display when it belongs to a window. */
    fun onShowRequested(token: IBinder?) {
        if (YAMFManager.config.showImeInWindow) return
        // The token names the window exactly. A show the server starts on its own has none. After
        // the user closed the keyboard those server-side requests must not start it again; only a
        // request from a window may.
        val displayId = displayOf(token) ?: activeDisplay() ?: return
        if (!YAMFManager.hostsWindow(displayId)) return
        if (token == null && userClosed) return
        if (token != null) userClosed = false
        if (keptDisplayId != displayId) {
            Log.i(TAG, "keeping the tablet keyboard up for display $displayId")
        }
        keptDisplayId = displayId
        keptToken = token
    }

    /**
     * Value the framework must report for an app's IME request.
     *
     * The first request starts the keep, because the framework would otherwise cancel it before
     * the input method service ever sees it: with no IME insets source on the fallback display the
     * request reads back as withdrawn. A withdrawal that carries no tracker token is that same
     * misunderstanding and is overridden; one that carries a token belongs to the app, which is
     * done typing, so the keep ends instead.
     */
    fun imeRequestedValue(requested: Boolean, token: Any?, target: Any?): Boolean {
        if (YAMFManager.config.showImeInWindow) return requested
        if (!requested) {
            if (token == null) return keepsTabletKeyboard
            onHideRequested()
            return false
        }
        // A request without a token is the display re-asking on its own, which must not reopen
        // what the user closed; a tokenised one comes from the app's window and does.
        if (token == null && userClosed) return false
        if (token != null) userClosed = false
        keepActiveDisplay()
        // Remembered so this window's repeats can be answered without work; dropped with the keep
        // so no window outlives it through this reference.
        lastRequestTarget = target
        return true
    }

    /**
     * True when the framework's IME change listener would *hide* the keyboard for [target].
     *
     * The listener is the only route from the insets policy to the input method service, and it
     * serves both directions. A withdrawal has to be dropped while a window keeps the keyboard
     * (the fallback display reads every request back as withdrawn), but the show that follows the
     * app's own request must go through, or the keyboard is never asked to be drawn at all.
     */
    fun imeChangeWantsHide(target: Any?): Boolean =
        runCatching {
            target?.invokeMethodAs<Boolean>("isRequestedVisible", WindowInsets.Type.ime()) == false
        }.getOrDefault(true)

    /**
     * Result that makes a hide the framework started on its own report that it did nothing.
     *
     * The entry points do not agree on a return type and the framework casts the result back:
     * handing a `boolean`-returning hide a `Unit` crashes system_server with a
     * ClassCastException. "Nothing was hidden" is both the truth here and the type it expects.
     */
    fun skippedHideResult(member: java.lang.reflect.Member?): Any =
        if ((member as? java.lang.reflect.Method)?.returnType == Boolean::class.javaPrimitiveType) {
            false
        } else {
            Unit
        }

    /** The app asked to hide the keyboard (or lost its editor), so stop keeping it up. */
    fun onHideRequested() {
        if (keptDisplayId > 0) {
            Log.i(TAG, "releasing the tablet keyboard of display $keptDisplayId")
        }
        userClosed = true
        keptDisplayId = -1
        keptToken = null
        lastRequestTarget = null
        clearFallbackRequest()
    }

    /**
     * The window stopped keeping the keyboard: it closed, minimised or was collapsed.
     *
     * The keep is dropped *and* the keyboard hidden, because the framework's own hide for that
     * window arrives while the keep is still in force and is dropped as a misunderstanding: left
     * to itself the keyboard would stay drawn over whatever takes focus next.
     */
    fun onDisplayLost(displayId: Int) {
        if (keptDisplayId != displayId) return
        Log.i(TAG, "the window of display $displayId is gone, hiding its keyboard")
        releaseKeyboard()
    }

    /** Remembers the service the hooks are installed on, which a restart replaces. */
    fun attachService(service: Any?) {
        if (service != null && service !== immService) immService = service
    }

    /** Remembers one of the service's hide entry points, used to close the keyboard with back. */
    fun rememberHideMethod(method: java.lang.reflect.Method) {
        if (method !in hideMethods) hideMethods = hideMethods + method
    }

    /** Remembers one of the service's show entry points, used to ask for the keyboard on a tap. */
    fun rememberShowMethod(method: java.lang.reflect.Method) {
        // showSoftInput needs the app's own result receiver and cannot be driven from here.
        if (method.name == "showSoftInput") return
        if (method !in showMethods) showMethods = showMethods + method
    }

    /**
     * The user tapped a floating window.
     *
     * An app on one of our displays cannot ask for the keyboard twice: it has no IME insets source
     * of its own, so the framework hands it no control with a leash, and every later show request
     * is dropped inside the app before it reaches this process. A tap that lands while the app
     * still asks for the keyboard is therefore the only signal a second request produces, and it is
     * enough to ask the input method service for the keyboard directly.
     */
    fun onWindowTapped(displayId: Int) {
        if (YAMFManager.config.showImeInWindow) return
        if (!userClosed || displayId <= 0 || !YAMFManager.hostsWindow(displayId)) return
        if (!windowAsksForKeyboard(displayId)) return
        Log.i(TAG, "the user tapped display $displayId, asking for the keyboard again")
        userClosed = false
        keptDisplayId = displayId
        keptToken = null
        // The tap asks for a keyboard that was closed, and closing it also took the request off
        // the tablet display: without it back the source stays invisible and the input method
        // service reports a shown keyboard that the window manager never draws.
        setFallbackRequest(true)
        showTabletKeyboard()
    }

    /** True while the window in focus on [displayId] still wants the keyboard. */
    private fun windowAsksForKeyboard(displayId: Int): Boolean = runCatching {
        val root = Instances.iWindowManager.getObject("mRoot") ?: return@runCatching false
        @Suppress("UNCHECKED_CAST")
        val displays = root.getObject("mChildren") as? List<Any> ?: return@runCatching false
        val display = displays.firstOrNull { it.getObjectAs<Int>("mDisplayId") == displayId }
            ?: return@runCatching false
        val focus = display.getObject("mCurrentFocus") ?: return@runCatching false
        focus.getObjectAs<Int>("mRequestedVisibleTypes") and IME_TYPE != 0
    }.onFailure { Log.w(TAG, "cannot read the keyboard request of display $displayId", it) }
        .getOrDefault(false)

    /** Asks the input method service to draw the keyboard for its current client. */
    private fun showTabletKeyboard() {
        val service = immService
        // A show entry point that returns false declines the request, so the next one is tried; one
        // that returns nothing has taken it.
        val shown = service != null &&
                invokeFirst(showMethods, service, null) { it as? Boolean ?: true }
        if (!shown) Log.w(TAG, "cannot ask for the tablet keyboard")
    }

    /**
     * Calls the first entry point that takes the request, with [token] in the parameter that
     * accepts one and default values in the rest.
     *
     * The service declares these over several overloads and none of them takes anything else a
     * caller here can supply. [accepted] decides whether a returned value means the call took
     * effect: a show reports false when it declines, while a hide returns nothing at all.
     */
    private fun invokeFirst(
        methods: List<java.lang.reflect.Method>,
        service: Any,
        token: IBinder?,
        accepted: (Any?) -> Boolean,
    ): Boolean {
        for (method in methods) {
            val args = Array<Any?>(method.parameterCount) { index ->
                val type = method.parameterTypes[index]
                when {
                    IBinder::class.java.isAssignableFrom(type) -> token
                    type == Int::class.javaPrimitiveType -> 0
                    type == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
            val took = runCatching {
                method.isAccessible = true
                accepted(method.invoke(service, *args))
            }.getOrElse {
                Log.w(TAG, "cannot call $method", it)
                false
            }
            if (took) return true
        }
        return false
    }

    /**
     * Hides the keyboard kept up for a window.
     *
     * @return true when a kept keyboard was released, so the caller must consume the key press.
     */
    fun hideTabletKeyboard(): Boolean {
        if (!keepsTabletKeyboard) return false
        releaseKeyboard()
        return true
    }

    /**
     * Drops the keep and hides the keyboard it held up.
     *
     * The window's own token is tried first and the current client second: by the time a closing
     * window reports back its token may already be gone, and a hide that names nothing still
     * closes the keyboard that was drawn.
     */
    private fun releaseKeyboard() {
        userClosed = true
        val service = immService
        val token = keptToken
        keptDisplayId = -1
        keptToken = null
        lastRequestTarget = null
        val hidden = service != null &&
                (invokeFirst(hideMethods, service, token) { _ -> true } ||
                        invokeFirst(hideMethods, service, null) { _ -> true })
        if (!hidden) Log.w(TAG, "let the tablet keyboard go without hiding it")
        clearFallbackRequest()
    }

    /**
     * Takes the keyboard request off the tablet display, which draws the keyboard.
     *
     * The fallback display keeps reporting the IME as requested for as long as its own requested
     * visible types include it, and asks for the keyboard again the moment it is hidden. Clearing
     * the bit is what the display's own insets controller does when the keyboard goes away, and it
     * is what lets a closed keyboard stay closed until a window asks for it again.
     */
    private fun clearFallbackRequest() = setFallbackRequest(false)

    /**
     * Sets or clears the keyboard bit of the tablet display's requested visible types.
     *
     * The window manager combines the two as `(requested & ~mask) | (types & mask)`, so both
     * directions use the keyboard's bit as the mask.
     */
    private fun setFallbackRequest(requested: Boolean) {
        // The key press that got here arrives on the shell's identity, which holds no window
        // manager permission; the call must run as this process instead.
        val identity = Binder.clearCallingIdentity()
        try {
            Instances.iWindowManager.invokeMethod(
                "updateDisplayWindowRequestedVisibleTypes",
                Display.DEFAULT_DISPLAY,
                if (requested) IME_TYPE else 0,
                IME_TYPE,
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cannot change the tablet display's keyboard request", t)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Starts keeping the keyboard for the display the user is working on. */
    private fun keepActiveDisplay() {
        val displayId = activeDisplay() ?: return
        if (keptDisplayId != displayId) {
            Log.i(TAG, "keeping the tablet keyboard up for display $displayId")
            keptDisplayId = displayId
            keptToken = null
        }
    }

    /** Display of the window the user is working on, or null when no window is open. */
    private fun activeDisplay(): Int? =
        YAMFManager.currentDisplayId.takeIf { YAMFManager.hostsWindow(it) }
            ?: YAMFManager.activeWindow()?.displayId

    /**
     * Display that owns the window [token] belongs to.
     *
     * InputMethodManagerService only sees the window token, so the display is read from the window
     * manager's token map. A missing entry means the window is gone, and nothing is kept up.
     */
    private fun displayOf(token: IBinder?): Int? {
        token ?: return null
        // An app asks again on every touch of a served field, so this runs several times per tap:
        // the field, the accessor and the last answer are all remembered instead of looked up.
        if (token === lastToken) return lastTokenDisplayId.takeIf { it > 0 }
        return runCatching {
            val field = windowMapField ?: Instances.iWindowManager.javaClass
                .findFieldOrNull("mWindowMap")
                ?.also { windowMapField = it }
            @Suppress("UNCHECKED_CAST")
            val windows = field?.get(Instances.iWindowManager) as? Map<IBinder, Any>
                ?: return@runCatching null
            val window = windows[token] ?: return@runCatching null
            val method = windowDisplayIdMethod ?: window.javaClass
                .findMethodOrNull { name == "getDisplayId" }
                ?.also { windowDisplayIdMethod = it }
            val displayId = method?.invoke(window) as? Int ?: return@runCatching null
            lastToken = token
            lastTokenDisplayId = displayId
            displayId
        }.onFailure { Log.e(TAG, "cannot resolve the display of $token", it) }.getOrNull()
    }
}
