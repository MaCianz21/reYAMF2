package com.mja.reyamf.xposed.services

import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowInsets
import com.mja.reyamf.common.reflect.findFieldOrNull
import com.mja.reyamf.common.reflect.findMethodOrNull
import com.mja.reyamf.common.reflect.getObject
import com.mja.reyamf.common.reflect.getObjectAs
import com.mja.reyamf.common.reflect.invokeMethod
import com.mja.reyamf.common.reflect.invokeMethodAs
import com.mja.reyamf.common.reflect.runOnMainThread
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

    /** Time a tap inside a window gets to ask for the keyboard before the tap closes it. */
    private const val TAP_HIDE_DELAY_MS = 250L

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

    /** Display whose app asked for the tablet keyboard, or -1 when none did. */
    @Volatile
    private var keptDisplayId = -1

    /** Window that asked for the keyboard, so it can be told to hide it again. */
    @Volatile
    private var keptToken: IBinder? = null

    /** Number of requests a window has made, so a hide posted before one of them is stale. */
    @Volatile
    private var showGeneration = 0

    /** Lookups the show path needs on every request, resolved once. */
    @Volatile
    private var windowMapField: java.lang.reflect.Field? = null
    @Volatile
    private var inputShownField: java.lang.reflect.Field? = null
    @Volatile
    private var windowDisplayIdMethod: java.lang.reflect.Method? = null
    @Volatile
    private var requestedTypesMethod: java.lang.reflect.Method? = null
    @Volatile
    private var requestedTypesField: java.lang.reflect.Field? = null
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

    /** Set while this thread hides the keyboard, whose own hooked calls would come back here. */
    private val releasing: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** The system's looper, for a hide the window manager's thread must not make itself. */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * True while the tablet keyboard must stay requested for a window's display. Only the fallback
     * policy needs the help; with the in-window keyboard the framework does the right thing alone.
     */
    val keepsTabletKeyboard: Boolean
        get() = keptDisplayId > 0 &&
                !YAMFManager.config.showImeInWindow &&
                YAMFManager.hostsWindow(keptDisplayId)

    /**
     * True when a hide the framework started is the fallback display's misunderstanding and must
     * be dropped, which is only while the window it kept the keyboard for still holds an editor.
     *
     * Once that focus is gone (the user submitted, tapped elsewhere, or left the screen) the hide
     * is the truth: the keep is released and the hide goes through, or the keyboard would stay up
     * over whatever took the focus.
     */
    fun keepAnswersHide(): Boolean {
        if (!keepsTabletKeyboard) return false
        val wantsIme = keptWindowRequestsIme()
        val editor = keptDisplayHasImeFocus()
        Log.i(TAG, "hide for display $keptDisplayId: app asks for ime=$wantsIme editor=$editor")
        if (wantsIme && editor) {
            Log.i(TAG, "dropped a hide the fallback display invented")
            return true
        }
        Log.i(TAG, "the window of display $keptDisplayId is done typing, letting the hide through")
        onHideRequested()
        return false
    }

    /**
     * True when the window that asked for the keyboard still lists it among the types it asked to
     * see. That is the app's own request, which the fallback display does not read back, so it is
     * what tells the hide the framework invented from the hide the app meant.
     *
     * A framework that does not expose the request answers true, which keeps the old behaviour.
     */
    private fun keptWindowRequestsIme(): Boolean {
        val token = keptToken ?: return true
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val windows = windowMapField?.get(Instances.iWindowManager) as? Map<IBinder, Any>
                ?: return@runCatching true
            val window = windows[token] ?: return@runCatching true
            val method = requestedTypesMethod ?: window.javaClass
                .findMethodOrNull { name == "getRequestedVisibleTypes" }
                ?.also { requestedTypesMethod = it }
            if (method != null) {
                return@runCatching ((method.invoke(window) as? Int) ?: 0) and IME_TYPE != 0
            }
            val field = requestedTypesField ?: window.javaClass
                .findFieldOrNull("mRequestedVisibleTypes")
                ?.also { requestedTypesField = it }
                ?: return@runCatching true
            ((field.get(window) as? Int) ?: 0) and IME_TYPE != 0
        }.getOrDefault(true)
    }

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

    /**
     * True when the kept window still holds an editable view's focus.
     *
     * The window manager drops a display's IME input target as soon as no editable view there has
     * the focus, which is what a submit or a tap outside the field leaves behind. A framework that
     * does not expose the target cannot tell, so it answers true.
     */
    private fun keptDisplayHasImeFocus(): Boolean = runCatching {
        val display = displayContent(keptDisplayId) ?: return@runCatching true
        val field = display.javaClass.findFieldOrNull("mImeInputTarget") ?: return@runCatching true
        field.get(display) != null
    }.getOrDefault(true)

    /** The window manager's record of [displayId], or null when it cannot be read. */
    private fun displayContent(displayId: Int): Any? = runCatching {
        val root = Instances.iWindowManager.getObject("mRoot") ?: return@runCatching null
        @Suppress("UNCHECKED_CAST")
        val displays = root.getObject("mChildren") as? List<Any> ?: return@runCatching null
        displays.firstOrNull { it.getObjectAs<Int>("mDisplayId") == displayId }
    }.getOrNull()

    /**
     * Top of the tablet's keyboard on the tablet display, or 0 when it is not drawn there.
     *
     * The insets state of the tablet's display is where the framework keeps the keyboard's frame;
     * the window itself is laid out full screen, so its own frame says nothing about the part of
     * the screen the keyboard covers.
     */
    fun tabletKeyboardTop(): Int {
        val display = displayContent(Display.DEFAULT_DISPLAY) ?: return 0
        return runCatching {
            val controller = display.getObject("mInsetsStateController") ?: return@runCatching 0
            val provider = controller.invokeMethodAs<Any?>("getImeSourceProvider")
                ?: return@runCatching 0
            // The provider keeps the frame the keyboard occupies on the display it is drawn on,
            // which is empty while the keyboard is hidden.
            val frame = sourceFrameOf(provider) ?: return@runCatching 0
            if (frame.isEmpty) 0 else frame.top.coerceAtLeast(0)
        }.onFailure { Log.w(TAG, "cannot read the keyboard's place on the tablet", it) }
            .getOrDefault(0)
    }

    /** The keyboard's frame from its insets source provider, by field and then by accessor. */
    private fun sourceFrameOf(provider: Any): Rect? {
        (provider.getObject("mSourceFrame") as? Rect)?.let { return it }
        (provider.getObject("mLastSourceFrame") as? Rect)?.let { return it }
        return runCatching {
            provider.invokeMethodAs<Any?>("getSource")?.invokeMethodAs<Rect>("getFrame")
        }.getOrNull()
    }

    /** Tells the window on [displayId] that the tablet's keyboard is up, or that it is gone. */
    private fun tellWindow(displayId: Int, shown: Boolean) {
        val window = YAMFManager.windowOf(displayId) ?: return
        runOnMainThread { runCatching { window.onTabletKeyboardChanged(shown) } }
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
        if (token == null && userClosed) {
            Log.i(TAG, "dropped a show the tablet display started after the user closed it")
            return
        }
        if (token != null) userClosed = false
        if (keptDisplayId != displayId) {
            Log.i(TAG, "keeping the tablet keyboard up for display $displayId")
        }
        keptDisplayId = displayId
        keptToken = token
        // A hide posted before this request was for the keyboard that is now asked for again.
        showGeneration++
        // The keyboard animates in, so the window is told where it ends up once it is drawn,
        // and again after the animation in case the first report caught it half way up.
        mainHandler.postDelayed({ if (keptDisplayId == displayId) tellWindow(displayId, true) }, 450)
        mainHandler.postDelayed({ if (keptDisplayId == displayId) tellWindow(displayId, true) }, 1100)
    }

    /**
     * Value the framework must report for an app's IME request.
     *
     * The first request starts the keep, because the framework would otherwise cancel it before
     * the input method service ever sees it: with no IME insets source on the fallback display the
     * request reads back as withdrawn. A withdrawal that carries no tracker token is that same
     * misunderstanding, but only while the window still holds an editor: a tokenless withdrawal
     * after a submit or a tap outside the field is the truth. One that carries a token is the
     * app's own, so it always ends the keep.
     */
    fun imeRequestedValue(requested: Boolean, token: Any?, target: Any?): Boolean {
        if (YAMFManager.config.showImeInWindow) return requested
        if (!requested) {
            val focused = keepsTabletKeyboard && keptDisplayHasImeFocus()
            val wantsIme = keptWindowRequestsIme()
            Log.i(TAG, "withdrawal token=${token != null} keep=$keptDisplayId editor=$focused " +
                    "appWants=$wantsIme")
            // The app's own request is what the override protects: once it no longer asks for the
            // keyboard, the withdrawal is the truth and the keyboard has to go.
            if (token == null && focused && wantsIme) return true
            // With no window of ours on screen the withdrawal belongs to a fullscreen app and is
            // none of this bridge's business.
            if (activeDisplay() != null) hideLater()
            return false
        }
        Log.i(TAG, "show request token=${token != null} userClosed=$userClosed")
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
        dropKeep()
    }

    /** Forgets the window the keyboard was kept up for, and takes its request off the tablet. */
    private fun dropKeep() {
        // With no window keeping the keyboard up nothing was asked for on the tablet display:
        // another app's keyboard (or a hide that came before any show) must be left alone.
        val wasKeeping = keptDisplayId > 0
        val lostDisplay = keptDisplayId
        userClosed = true
        keptDisplayId = -1
        keptToken = null
        lastRequestTarget = null
        if (wasKeeping) {
            clearFallbackRequest()
            // The keyboard takes a moment to leave; the window takes its room back afterwards.
            mainHandler.postDelayed({
                if (!tabletKeyboardUp()) tellWindow(lostDisplay, false)
            }, 300)
        }
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

    /**
     * Calls the first entry point that takes the request, with [token] in the parameter that
     * accepts one and default values in the rest.
     *
     * The service declares these over several overloads and none of them takes anything else a
     * caller here can supply. A hide reports nothing that says whether it took effect, so a call
     * that returns without throwing counts as taken.
     */
    private fun invokeFirst(
        methods: List<java.lang.reflect.Method>,
        service: Any,
        token: IBinder?,
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
            val called = runCatching {
                method.isAccessible = true
                method.invoke(service, *args)
                true
            }.getOrElse {
                Log.w(TAG, "cannot call $method", it)
                false
            }
            if (called) return true
        }
        return false
    }

    /**
     * True while the tablet's keyboard is up for a window.
     *
     * The keep can already be gone while the keyboard it drew is still on screen, so the keyboard
     * the service reports is the other half of the answer: without it the back press would be
     * forwarded to the app while the keyboard is still open, and the app's own hide, which the
     * fallback display swallows, would look like a hide of nothing.
     */
    private fun tabletKeyboardUp(): Boolean =
        !YAMFManager.config.showImeInWindow && (keepsTabletKeyboard || inputShown(immService))

    /**
     * A tap landed inside a window while the tablet keyboard is up.
     *
     * Tapping the field the user is typing in, or another one, makes the app ask for the keyboard
     * again; that request is seen here and cancels the hide. Any other tap is the user leaving the
     * field. Apps rely on the window manager to take the keyboard away in that case, which the
     * fallback display never does, so the keyboard drawn on the tablet has to be closed here.
     */
    fun onWindowTapped(displayId: Int) {
        if (!tabletKeyboardUp() || keptDisplayId != displayId) return
        val generation = showGeneration
        mainHandler.postDelayed({
            // A request for the keyboard after the tap means it was a field after all.
            if (generation == showGeneration && keptDisplayId == displayId && tabletKeyboardUp()) {
                Log.i(TAG, "a tap inside the window left the field, hiding the tablet keyboard")
                releaseKeyboard()
            }
        }, TAP_HIDE_DELAY_MS)
    }

    /**
     * Hides the keyboard kept up for a window.
     *
     * @return true when a keyboard was closed, so the caller must consume the key press.
     */
    fun hideTabletKeyboard(): Boolean {
        if (!tabletKeyboardUp()) return false
        releaseKeyboard()
        return true
    }

    /**
     * The app itself asked for the keyboard to go: a submit, a tap outside the field, or its own
     * hide call.
     *
     * With the fallback policy the app's hide names a window on the window's display, while the
     * keyboard is drawn on the tablet's, so on its own it closes nothing. The hide has to be
     * repeated for the tablet keyboard here, or the keyboard stays up over the window after a
     * submit. A hide that names another display's window, or an app that is not in a window at
     * all, is somebody else's keyboard and is left alone.
     */
    fun onClientHideRequested(token: IBinder?) {
        val displayId = displayOf(token)
        if (displayId != null) {
            if (!YAMFManager.hostsWindow(displayId)) return
            if (keptDisplayId > 0 && displayId != keptDisplayId) return
        }
        Log.i(TAG, "app hide: window display=${displayId ?: -1} keep=$keptDisplayId up=${tabletKeyboardUp()}")
        if (!tabletKeyboardUp()) return
        releaseKeyboard()
    }

    /**
     * Closes the keyboard a window kept up, for a caller that must not wait for the input method
     * service: the window manager reports a withdrawal while it holds its lock, and the service
     * calls back into the window manager.
     */
    private fun hideLater() {
        Log.i(TAG, "the app withdrew the keyboard, closing it once the window manager returns")
        if (!tabletKeyboardUp()) {
            dropKeep()
            return
        }
        val generation = showGeneration
        dropKeep()
        runCatching {
            mainHandler.post {
                // A window asked for the keyboard again after this hide was posted; the keyboard
                // it asked for is not the one this hide was meant to close.
                if (generation == showGeneration && tabletKeyboardUp()) releaseKeyboard()
            }
        }.onFailure { Log.w(TAG, "cannot post the keyboard hide", it) }
    }

    /**
     * Drops the keep and hides the keyboard it held up.
     *
     * The window's own token is tried first and the current client second: by the time a closing
     * window reports back its token may already be gone, and a hide that names nothing still
     * closes the keyboard that was drawn.
     *
     * The hide entry points are hooked and their hooks ask this bridge what to do, so a hide
     * started here must not be taken for a new request from the app.
     */
    private fun releaseKeyboard() {
        if (releasing.get()) return
        releasing.set(true)
        try {
            val token = keptToken
            // The keep goes even when the service cannot be reached: it is what makes the tablet
            // display look like it still wants a keyboard, and a stale one outlives the service.
            dropKeep()
            val service = immService ?: return
            // The token names a window on the window's display, where the fallback keyboard is
            // not, so that call can return having closed nothing: the current client is asked as
            // well, and hiding what is already hidden costs nothing.
            val byToken = token != null && invokeFirst(hideMethods, service, token)
            val current = invokeFirst(hideMethods, service, null)
            if (!current && !byToken) {
                Log.w(TAG, "let the tablet keyboard go without hiding it")
            } else {
                Log.i(TAG, "closed the tablet keyboard (token=$byToken current=$current)")
            }
        } finally {
            releasing.set(false)
        }
    }

    /**
     * Takes the keyboard request off the tablet display, which draws the keyboard.
     *
     * The fallback display keeps reporting the IME as requested for as long as its own requested
     * visible types include it, and asks for the keyboard again the moment it is hidden. Clearing
     * the bit is what the display's own insets controller does when the keyboard goes away, and it
     * is what lets a closed keyboard stay closed until a window asks for it again.
     */
    private fun clearFallbackRequest() {
        // The key press that got here arrives on the shell's identity, which holds no window
        // manager permission; the call must run as this process instead.
        val identity = Binder.clearCallingIdentity()
        try {
            Instances.iWindowManager.invokeMethod(
                "updateDisplayWindowRequestedVisibleTypes",
                Display.DEFAULT_DISPLAY,
                0,
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
        // A hide posted before this request was for the keyboard that is now asked for again.
        showGeneration++
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
