package com.mja.reyamf.xposed.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.mja.reyamf.common.reflect.argTypes
import com.mja.reyamf.common.reflect.args
import com.mja.reyamf.common.reflect.invokeMethod
import com.mja.reyamf.common.reflect.invokeMethodAs
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.WindowRegistry
import com.mja.reyamf.common.HookPlatform
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.runIO
import com.mja.reyamf.common.runMain
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.IOpenCountListener
import com.mja.reyamf.xposed.IYAMFManager
import com.mja.reyamf.xposed.hook.LauncherHooksConfig
import com.mja.reyamf.xposed.ui.window.AppWindow
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.Instances.systemContext
import com.mja.reyamf.xposed.utils.Instances.systemUiContext
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.createContext
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import com.mja.reyamf.xposed.utils.getTopRootTask
import com.mja.reyamf.xposed.utils.log
import com.mja.reyamf.xposed.utils.registerReceiver
import com.mja.reyamf.xposed.utils.startAuto
import com.qauxv.ui.CommonContextWrapper
import rikka.hidden.compat.ActivityManagerApis
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "reYAMFManager"

    const val ACTION_GET_LAUNCHER_CONFIG = "com.mja.reyamf.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.mja.reyamf.action.OPEN_APP"
    private const val ACTION_CURRENT_TO_WINDOW = "com.mja.reyamf.action.CURRENT_TO_WINDOW"
    private const val ACTION_OPEN_APP_LIST = "com.mja.reyamf.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.mja.reyamf.ACTION_OPEN_IN_YAMF"

    /** How long after using a window a taskbar back press still belongs to it. */
    private const val BACK_PRESS_USE_MS = 60_000L

    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"

    private const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    private val windows = WindowRegistry()

    /** Live windows keyed by the virtual display they host, so a second request can reuse one. */
    private val windowsByDisplay = ConcurrentHashMap<Int, AppWindow>()

    /** Display of the window the user interacted with last, or -1 when there is none. */
    @Volatile
    private var activeDisplayId = -1

    /** When each window was last used, keyed by the virtual display it hosts. */
    private val lastUseAt = ConcurrentHashMap<Int, Long>()

    /**
     * A default value keeps early calls (app queried the config before systemReady) from
     * throwing UninitializedPropertyAccessException inside system_server.
     */
    @Volatile
    var config: Config = Config()

    val configFile = File("/data/system/reYAMF.json")

    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    lateinit var activityManagerService: Any
    var currentDisplayId = 0

    fun systemReady() {
        runCatching { loadConfig() }
            .onFailure { log(TAG, "failed to load config", it) }
        runCatching { Instances.init(activityManagerService) }
            .onFailure { log(TAG, "failed to initialise system services", it) }
        registerSystemReceivers()
        log(TAG, "system ready, config: $config")
    }

    private fun loadConfig() {
        runCatching { if (!configFile.exists()) configFile.createNewFile() }
            .onFailure { log(TAG, "cannot create ${configFile.absolutePath}", it) }
        val text = runCatching { configFile.readText() }.getOrNull().orEmpty()
        config = if (text.isBlank()) {
            Config()
        } else {
            runCatching { gson.fromJson(text, Config::class.java) }
                .onFailure { log(TAG, "stored config is unreadable, using defaults", it) }
                .getOrNull() ?: Config()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemReceivers() {
        val context = runCatching { systemContext }.getOrElse {
            log(TAG, "system context unavailable, broadcasts will not work", it)
            return
        }

        runCatching {
            context.registerReceiver(ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        }.onFailure { log(TAG, "cannot register $ACTION_OPEN_IN_YAMF", it) }

        runCatching {
            context.registerReceiver(ACTION_CURRENT_TO_WINDOW) { _, _ -> currentToWindow() }
        }.onFailure { log(TAG, "cannot register $ACTION_CURRENT_TO_WINDOW", it) }

        runCatching {
            context.registerReceiver(ACTION_OPEN_APP_LIST) { _, _ -> openAppList() }
        }.onFailure { log(TAG, "cannot register $ACTION_OPEN_APP_LIST", it) }

        runCatching {
            context.registerReceiver(ACTION_OPEN_APP) { _, intent ->
                val componentName = intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
                    ?: return@registerReceiver
                val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
                createWindow(StartCmd(componentName = componentName, userId = userId))
            }
        }.onFailure { log(TAG, "cannot register $ACTION_OPEN_APP", it) }

        runCatching {
            context.registerReceiver(ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
                runCatching {
                    ActivityManagerApis.broadcastIntent(Intent(LauncherHooksConfig.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                        log(TAG, "send config: ${config.hookLauncher}")
                        putExtra(LauncherHooksConfig.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                        putExtra(LauncherHooksConfig.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                        putExtra(LauncherHooksConfig.EXTRA_HOOK_POPUP, config.hookLauncher.hookPopup)
                        putExtra(LauncherHooksConfig.EXTRA_HOOK_TRANSIENT_TASKBAR, config.hookLauncher.hookTransientTaskbar)
                        `package` = intent.getStringExtra("sender")
                    }, 0)
                }.onFailure { log(TAG, "cannot send the launcher config", it) }
            }
        }.onFailure { log(TAG, "cannot register $ACTION_GET_LAUNCHER_CONFIG", it) }
    }

    private fun notifyOpenCount() {
        val toRemove = mutableSetOf<IOpenCountListener>()
        val count = windows.count
        iOpenCountListenerSet.toList().forEach {
            runCatching { it.onUpdate(count) }.onFailure { _ -> toRemove.add(it) }
        }
        iOpenCountListenerSet.removeAll(toRemove)
    }

    private fun addWindow(id: Int) {
        windows.add(id)
        notifyOpenCount()
    }

    /** Registers a freshly created window and makes it the current navigation bar target. */
    fun registerWindow(displayId: Int, window: AppWindow) {
        windowsByDisplay[displayId] = window
        activeDisplayId = displayId
    }

    fun removeWindow(id: Int) {
        windowsByDisplay.remove(id)
        lastUseAt.remove(id)
        if (activeDisplayId == id) activeDisplayId = -1
        ImeVisibilityBridge.onDisplayLost(id)
        if (windows.remove(id)) notifyOpenCount()
    }

    /** True when [displayId] hosts one of the floating windows. */
    fun hostsWindow(displayId: Int): Boolean = windowsByDisplay.containsKey(displayId)

    /** The floating window hosted on [displayId], or null when none is open there. */
    fun windowOf(displayId: Int): AppWindow? = windowsByDisplay[displayId]

    /** Remembers which window the navigation bar buttons must act on, and when it was used. */
    fun onWindowUsed(displayId: Int) {
        if (!windowsByDisplay.containsKey(displayId)) return
        activeDisplayId = displayId
        lastUseAt[displayId] = SystemClock.uptimeMillis()
    }

    /**
     * The window a back press from the tablet's taskbar should navigate, if any.
     *
     * The taskbar's back button is not a key press: it asks the system to navigate back, and the
     * system hands that to the top task of the default display, which is where a floating window
     * is drawn but not where its app runs. The press belongs to the window only while the user is
     * working in it, so a window used within the last [BACK_PRESS_USE_MS] answers it, and a press
     * after the user moved on to another app is left to that app.
     */
    fun windowForBackPress(): AppWindow? {
        val window = activeWindow() ?: return null
        if (!window.canReceiveKeys) return null
        val usedAt = lastUseAt[window.displayId] ?: return null
        return window.takeIf { SystemClock.uptimeMillis() - usedAt < BACK_PRESS_USE_MS }
    }

    /**
     * Follows input focus between displays. Focus leaving the windows' displays (for the launcher,
     * another app, or a system panel) hands the navigation bar back to the system.
     */
    fun onDisplayFocusChanged(displayId: Int) {
        currentDisplayId = displayId
        // Only a display that hosts a window may claim the navigation buttons. Focus moving to a
        // display without one (the launcher coming back after a window opens, or an app behind the
        // floating windows) must not steal them: the launcher resuming on the default display is
        // exactly what cleared the target right after a window was opened and left the buttons
        // acting on the system instead of the window.
        windowsByDisplay[displayId]?.let { activeDisplayId = displayId }
    }

    /**
     * The window the navigation bar buttons should act on, if any. Focus tracking can miss a
     * transient change, so a lone open window is used as a fallback instead of handing the
     * buttons to the app behind it.
     */
    fun activeWindow(): AppWindow? {
        windowsByDisplay[activeDisplayId]?.let { return it }
        return if (windowsByDisplay.size == 1) windowsByDisplay.values.firstOrNull() else null
    }

    /** Hands the navigation bar back to the system after the window stepped aside. */
    fun clearActiveWindow() {
        activeDisplayId = -1
    }

    fun isTop(id: Int) = windows.isTop(id)

    fun moveToTop(id: Int) = windows.moveToTop(id)

    fun createWindow(startCmd: StartCmd?) {
        // Tapping an app that is already open must raise its window instead of opening a second
        // one: a second window pulls the running task onto its own display and leaves the first
        // one empty, showing nothing but the mirrored system decorations.
        findWindowHosting(startCmd)?.let { existing ->
            existing.bringToFront(startCmd)
            return
        }
        runCatching { Instances.iStatusBarService.collapsePanels() }
            .onFailure { log(TAG, "cannot collapse the status bar panel", it) }
        runCatching {
            AppWindow(
                CommonContextWrapper.createAppCompatContext(systemUiContext.createContext()),
                config.flags
            ) { displayId ->
                addWindow(displayId)
                runCatching { startCmd?.startAuto(displayId) }
                    .onFailure { log(TAG, "cannot launch inside the new window", it) }
            }
        }.onFailure { log(TAG, "failed to create a floating window", it) }
    }

    private fun findWindowHosting(startCmd: StartCmd?): AppWindow? {
        startCmd ?: return null
        return windowsByDisplay.values.firstOrNull { it.hosts(startCmd) }
    }

    /** Opens the module's app switcher overlay, used by the navigation bar's recents button. */
    fun openAppList() {
        runCatching {
            val component = ComponentName(
                "com.mja.reyamf",
                "com.mja.reyamf.manager.applist.AppListWindow"
            )
            (HookPlatform.currentApplication() ?: systemContext)
                .startService(Intent().setComponent(component))
        }.onFailure { log(TAG, "cannot open the app list window", it) }
    }

    init {
        log(TAG, "reYAMF service initialized")
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun createWindow() {
        runMain { createWindow(null) }
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun getConfigJson(): String {
        return gson.toJson(config)
    }

    override fun updateConfig(newConfig: String) {
        val parsed = runCatching { gson.fromJson(newConfig, Config::class.java) }
            .onFailure { log(TAG, "ignoring malformed config", it) }
            .getOrNull() ?: return
        config = parsed
        // The IME policy is per display, so a change to "Show IME in window" only becomes visible
        // in windows that are already open when it is pushed to them.
        runMain { windowsByDisplay.values.forEach { it.applyImePolicy() } }
        runIO {
            runCatching { configFile.writeText(newConfig) }
                .onFailure { log(TAG, "cannot persist the config", it) }
            Log.d(TAG, "updateConfig: $config")
        }
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        iOpenCountListenerSet.add(iOpenCountListener)
        runCatching { iOpenCountListener.onUpdate(windows.count) }
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        iOpenCountListenerSet.remove(iOpenCountListener)
    }

    override fun currentToWindow() {
        runMain {
            runCatching {
                val task = getTopRootTask(0) ?: return@runMain

                if (task.baseActivity?.packageName != "com.android.launcher3") {
                    createWindow(StartCmd(taskId = task.taskId))
                }
            }.onFailure { log(TAG, "cannot windowfy the current task", it) }
        }
    }

    /**
     * Puts every window back to its starting position; it is deliberately not a close.
     * Only a window's own Close action reaches onDestroy(), and that is what releases the
     * virtual display. Folding the two together would turn "reset" into "destroy" for every
     * window the user has arranged, so the reset path owns no display and cannot leak one.
     */
    override fun resetAllWindow() {
        runMain {
            runCatching {
                Instances.iStatusBarService.collapsePanels()
                systemContext.sendBroadcast(Intent(AppWindow.ACTION_RESET_ALL_WINDOW))
            }.onFailure { log(TAG, "cannot reset the windows", it) }
        }
    }

    override fun getAppList(): List<AppInfo?>? {
        return listOf()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    /**
     * One unreadable package must not empty the whole list, so every app is resolved
     * independently and failures are skipped.
     */
    override fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            runCatching {
                val showApps: MutableList<AppInfo> = mutableListOf()
                val users = mutableMapOf<Int, String>()

                val userList = runCatching {
                    Instances.userManager.invokeMethodAs<List<UserInfo>>(
                        "getUsers",
                        args(true, true, true),
                        argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                    )
                }.onFailure { log(TAG, "cannot list users", it) }.getOrNull().orEmpty()

                userList
                    .filter { it.isProfile || it.isPrimary }
                    .forEach { users[it.id] = it.name }

                users.forEach { (userId, userName) ->
                    val apps = runCatching {
                        (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                            Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                            }, 0, userId
                        )
                    }.onFailure { log(TAG, "cannot query apps for user $userId", it) }
                        .getOrNull().orEmpty()

                    apps.forEach { resolveInfo ->
                        val component = ComponentName(
                            resolveInfo.activityInfo.packageName,
                            resolveInfo.activityInfo.name
                        )
                        val activityInfo = runCatching {
                            (Instances.iPackageManager as IPackageManagerHidden)
                                .getActivityInfoCompat(component, 0, userId)
                        }.getOrNull() ?: return@forEach
                        showApps.add(AppInfo(activityInfo, userId, userName))
                    }
                }

                showApps.chunked(5).forEach { chunk ->
                    runCatching { callback.onAppListReceived(chunk.toMutableList()) }
                        .onFailure { log(TAG, "app list callback failed", it) }
                }
                runCatching { callback.onAppListFinished() }
                    .onFailure { log(TAG, "app list callback failed", it) }
            }.onFailure { log(TAG, "failed to build the app list", it) }
        }
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            runCatching {
                val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

                val bitmap = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap
                    is AdaptiveIconDrawable -> {
                        val size = 108
                        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    }
                    else -> {
                        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    }
                }

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                callback.onResult(stream.toByteArray())
            }.onFailure { log(TAG, "cannot load the app icon", it) }
        }
    }

    override fun collapseStatusBarPanel() {
        runMain {
            runCatching { Instances.iStatusBarService.collapsePanels() }
                .onFailure { log(TAG, "cannot collapse the status bar panel", it) }
        }
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        { _: Context, intent: Intent ->
            val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_UNSPECIFIED)
            createWindow(StartCmd(componentName, userId, taskId))

            // TODO: better way to close recents
            if (source == SOURCE_RECENT && config.recentBackHome) {
                runCatching {
                    val down = KeyEvent(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_HOME,
                        0
                    ).apply {
                        this.source = InputDevice.SOURCE_KEYBOARD
                        this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                    }
                    Instances.inputManager.injectInputEvent(down, 0)
                    val up = KeyEvent(
                        SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_HOME,
                        0
                    ).apply {
                        this.source = InputDevice.SOURCE_KEYBOARD
                        this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                    }
                    Instances.inputManager.injectInputEvent(up, 0)
                }.onFailure { log(TAG, "cannot go back to home", it) }
            }
        }
}
