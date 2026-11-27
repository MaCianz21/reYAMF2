package com.mja.reyamf.xposed.hook

import android.app.AndroidAppHelper
import android.content.Intent
import android.content.pm.IPackageManager
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.HookPlatform
import com.mja.reyamf.xposed.services.ImeVisibilityBridge
import com.mja.reyamf.xposed.services.NavKeyRouter
import com.mja.reyamf.xposed.services.UserService
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.utils.log
import com.qauxv.util.Initiator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.concurrent.thread


class HookSystem : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        private const val TAG = "reYAMF_HookSystem"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
        // Shared module code logs and reaches the app context through this seam.
        HookPlatform.logSink = { _, tag, message, t ->
            XposedBridge.log("[$tag] $message")
            if (t != null) XposedBridge.log(t)
        }
        HookPlatform.applicationProvider = { AndroidAppHelper.currentApplication() }
        HookPlatform.moduleAssetPathAdder = { resources -> EzXHelperInit.addModuleAssetPath(resources) }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        log(TAG, "xposed init")
        log(TAG, "buildtype: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        EzXHelperInit.initHandleLoadPackage(lpparam)
        Initiator.init(lpparam.classLoader)

        var serviceManagerHook: XC_MethodHook.Unhook? = null
        runCatching {
            serviceManagerHook = findMethod("android.os.ServiceManager") {
                name == "addService"
            }.hookBefore { param ->
                if (param.args[0] == "package") {
                    serviceManagerHook?.unhook()
                    val pms = param.args[1] as IPackageManager
                    log(TAG, "Got pms: $pms")
                    thread {
                        runCatching {
                            UserService.register(pms)
                            log(TAG, "UserService started")
                        }.onFailure {
                            log(TAG, "UserService failed to start", it)
                        }
                    }
                }
            }
        }.onFailure { log(TAG, "cannot hook ServiceManager.addService", it) }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        runCatching {
            activityManagerServiceSystemReadyHook =
                findMethod("com.android.server.am.ActivityManagerService") {
                    name == "systemReady"
                }.hookAfter { param ->
                    activityManagerServiceSystemReadyHook?.unhook()
                    // Anything thrown here would abort system_server startup, so it is contained.
                    runCatching {
                        YAMFManager.activityManagerService = param.thisObject
                        YAMFManager.systemReady()
                        log(TAG, "system ready")
                    }.onFailure { log(TAG, "reYAMF failed to initialise", it) }
                }
        }.onFailure { log(TAG, "cannot hook ActivityManagerService.systemReady", it) }

        runCatching {
            findMethod("com.android.server.am.ActivityManagerService") {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit
            }
        }.onFailure {
            log(TAG, "ActivityManagerService checkBroadcastFromSystem fail", it)
        }

        runCatching {
            findMethod("com.android.server.am.BroadcastController") {
                name == "checkBroadcastFromSystem"
            }.hookBefore {
                val intent = it.args[0] as Intent
                if (intent.action == HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG)
                    it.result = Unit
            }
        }.onFailure {
            log(TAG, "BroadcastController checkBroadcastFromSystem fail", it)
        }

        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wm.InputMonitor",
                lpparam.classLoader
            )

            findAndHookMethod(
                clazz,
                "requestFocus",
                android.os.IBinder::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val displayId = XposedHelpers.getIntField(param.thisObject, "mDisplayId")
                            YAMFManager.onDisplayFocusChanged(displayId)
                        }.onFailure {
                            log(TAG, "cannot read the focused display", it)
                        }
                    }
                }
            )
        }.onFailure { e ->
            XposedBridge.log("[XposedHook] Error hooking requestFocus: ${e.message}")
        }

        // The navigation bar's three buttons (and the taskbar's copy of them) send their press
        // through InputManagerService, so this sees them before the policy hands them to a
        // window on the default display. That is the only place a floating window can redirect
        // them: its app lives on a virtual display the system's own handling never reaches.
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.input.InputManagerService",
                lpparam.classLoader
            )
            // Both the binder entry point and the target-specific method it delegates to are
            // hooked: the exact one called differs between releases, and either can be the first
            // to see a key.
            listOf("injectInputEvent", "injectInputEventToTarget").forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(
                        clazz,
                        methodName,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.args.isEmpty()) return
                                val event = param.args[0] as? InputEvent ?: return
                                runCatching {
                                    // Claim success: the press was delivered to the window instead.
                                    if (NavKeyRouter.onInjectedEvent(event)) param.result = true
                                }.onFailure { log(TAG, "cannot route the navigation key", it) }
                            }
                        }
                    )
                    log(TAG, "hooked InputManagerService.$methodName")
                }.onFailure { log(TAG, "cannot hook InputManagerService.$methodName", it) }
            }
        }.onFailure { log(TAG, "cannot hook InputManagerService", it) }

        // The tablet taskbar's back button is not a key press: Launcher asks the system to
        // navigate back, and the system navigates the top task of the default display, which is
        // the app drawn behind a floating window instead of the window's own app. Claiming the
        // call keeps a press the user made while working in a window inside that window.
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader
            )
            val method = clazz.methods.firstOrNull { it.name == "startBackNavigation" }
                ?: throw NoSuchMethodException("startBackNavigation")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        // A null navigation info leaves the caller nothing to animate or to
                        // navigate, which is what claims the press.
                        if (NavKeyRouter.onBackPressed()) param.result = null
                    }.onFailure { log(TAG, "cannot route the taskbar back button", it) }
                }
            })
            log(TAG, "hooked ActivityTaskManagerService.startBackNavigation")
        }.onFailure { log(TAG, "cannot hook the taskbar back button", it) }

        // Keeps the tablet's own keyboard up for an app on a window's display. The fallback IME
        // policy draws it on the tablet, but the framework reads the app's request back through
        // the app display's own IME insets source, which a fallback display does not have: the
        // request counts as withdrawn and the keyboard is hidden again right after being drawn.
        // These hooks keep that request true while ImeVisibilityBridge holds a window's keyboard,
        // and let the app's own hide request and the back press close it.
        runCatching {
            val immService = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                lpparam.classLoader
            )
            // Every show and hide entry point is hooked: the server shows the keyboard on its own
            // when the app's input target gains focus, only some paths carry a window token, and
            // the token is not always the first parameter.
            val showMethods = ImeVisibilityBridge.SHOW_METHODS
            val hideMethods = ImeVisibilityBridge.HIDE_METHODS
            (showMethods + hideMethods).forEach { methodName ->
                runCatching {
                    if (methodName in hideMethods) {
                        immService.declaredMethods
                            .filter { it.name == methodName }
                            .forEach { ImeVisibilityBridge.rememberHideMethod(it) }
                    }
                    XposedBridge.hookAllMethods(
                        immService,
                        methodName,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    ImeVisibilityBridge.attachService(param.thisObject)
                                    // A window token names the window a request is for, in the
                                    // show entry points and in the app's own hide alike.
                                    val token =
                                        param.args.firstOrNull { it is IBinder } as? IBinder
                                    when {
                                        methodName in showMethods -> {
                                            if (ImeVisibilityBridge.dropsShowRequest(token)) {
                                                param.result = false
                                            } else if (ImeVisibilityBridge.alreadyShown(
                                                    param.thisObject,
                                                    token
                                                )
                                            ) {
                                                // Already drawn for this window: nothing to do.
                                                param.result = true
                                            } else {
                                                ImeVisibilityBridge.onShowRequested(token)
                                            }
                                        }

                                        // The app itself is done typing: the fallback display
                                        // drew the keyboard elsewhere, so its own hide closes
                                        // nothing unless it is repeated for the tablet keyboard.
                                        methodName == ImeVisibilityBridge.CLIENT_HIDE ->
                                            ImeVisibilityBridge.onClientHideRequested(token)

                                        // The server hides the keyboard because it believes
                                        // the app withdrew the request, which is the
                                        // misunderstanding these hooks exist for. A hide
                                        // that follows the window losing its editor is the
                                        // truth.
                                        ImeVisibilityBridge.keepAnswersHide() ->
                                            param.result =
                                                ImeVisibilityBridge.skippedHideResult(param.method)
                                    }
                                }.onFailure { Log.e(TAG, "cannot track $methodName", it) }
                            }
                        }
                    )
                }.onFailure { Log.e(TAG, "cannot hook InputMethodManagerService.$methodName", it) }
            }
            Log.i(TAG, "hooked the keyboard requests of InputMethodManagerService")
        }.onFailure { Log.e(TAG, "cannot load InputMethodManagerService", it) }

        listOf(
            "com.android.server.wm.DisplayContent\$RemoteInsetsControlTarget",
            "com.android.server.wm.WindowState"
        ).forEach { className ->
            runCatching {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedBridge.hookAllMethods(
                    clazz,
                    "setImeInputTargetRequestedVisibility",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val requested = param.args.firstOrNull() as? Boolean
                                    ?: return@runCatching
                                // A repeat of a request that already holds needs no work.
                                if (ImeVisibilityBridge.repeatsActiveRequest(
                                        requested,
                                        param.thisObject
                                    )
                                ) {
                                    param.result = Unit
                                    return@runCatching
                                }
                                // A first request starts the keep, and every later withdrawal for
                                // the same display is overridden.
                                param.args[0] = ImeVisibilityBridge.imeRequestedValue(
                                    requested,
                                    param.args.getOrNull(1),
                                    param.thisObject
                                )
                            }.onFailure { Log.e(TAG, "cannot keep the IME requested", it) }
                        }
                    }
                )
                Log.i(TAG, "hooked $className.setImeInputTargetRequestedVisibility")
            }.onFailure {
                Log.e(TAG, "cannot hook $className.setImeInputTargetRequestedVisibility", it)
            }
        }

        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wm.ImeInsetsSourceProvider",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(
                clazz,
                "invokeOnImeRequestedChangedListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            // The listener would take the withdrawn request to the input method
                            // service. It carries both directions: only a withdrawal is dropped
                            // while a window keeps the keyboard, the app's show must go through.
                            if (ImeVisibilityBridge.keepAnswersHide() &&
                                ImeVisibilityBridge.imeChangeWantsHide(param.args.getOrNull(0))
                            ) {
                                param.result = Unit
                            }
                        }.onFailure { Log.e(TAG, "cannot skip the IME change listener", it) }
                    }
                }
            )
            Log.i(TAG, "hooked ImeInsetsSourceProvider.invokeOnImeRequestedChangedListener")
        }.onFailure { Log.e(TAG, "cannot hook ImeInsetsSourceProvider", it) }
    }
}
