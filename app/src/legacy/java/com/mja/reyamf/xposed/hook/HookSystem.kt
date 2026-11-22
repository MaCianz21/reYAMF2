package com.mja.reyamf.xposed.hook

import android.app.AndroidAppHelper
import android.content.Intent
import android.content.pm.IPackageManager
import android.view.InputEvent
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.HookPlatform
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
            XposedBridge.hookAllMethods(
                clazz,
                "injectInputEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 2) return
                        val event = param.args[0] as? InputEvent ?: return
                        runCatching {
                            // Claim success: the press was delivered to the window instead.
                            if (NavKeyRouter.onInjectedEvent(event)) param.result = true
                        }.onFailure { log(TAG, "cannot route the navigation key", it) }
                    }
                }
            )
        }.onFailure { log(TAG, "cannot hook InputManagerService.injectInputEvent", it) }
    }
}
