package com.mja.reyamf.xposed.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.view.InputEvent
import com.mja.reyamf.common.reflect.findMethod
import com.mja.reyamf.common.reflect.getObjectAs
import com.mja.reyamf.common.reflect.loadClass
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.xposed.services.NavKeyRouter
import com.mja.reyamf.xposed.services.UserService
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.utils.log
import com.qauxv.util.Initiator
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.hook
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import kotlin.concurrent.thread

/**
 * system_server hooks, ported from the legacy XposedBridge implementation to the libxposed API.
 *
 * Every registration is contained: a renamed framework member must not abort the remaining hooks
 * and must never abort system_server startup.
 */
object HookSystem {
    private const val TAG = "reYAMF_HookSystem"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        log(TAG, "xposed init")
        log(TAG, "buildtype: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        Initiator.init(classLoader)

        hookPackageService(module, classLoader)
        hookActivityManagerReady(module, classLoader)
        hookSystemBroadcastChecks(classLoader)
        hookInputFocus(module, classLoader)
        hookInjectedInput(module, classLoader)
    }

    private fun hookPackageService(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val addService = loadClass("android.os.ServiceManager", classLoader).findMethod {
                name == "addService"
            }
            // addService is called from heavily JIT-compiled startup code; force the runtime to
            // stop inlining it so the hook is actually reached on ART 17.
            deoptimize(module, addService)

            var handle: XposedInterface.HookHandle? = null
            handle = addService.hook { chain ->
                if (chain.args.getOrNull(0) == "package") {
                    handle?.unhook()
                    val pms = chain.args.getOrNull(1) as? IPackageManager
                    if (pms == null) {
                        log(TAG, "package service is not an IPackageManager")
                    } else {
                        log(TAG, "Got pms: $pms")
                        thread {
                            runCatching {
                                UserService.register(pms)
                                log(TAG, "UserService started")
                            }.onFailure { log(TAG, "UserService failed to start", it) }
                        }
                    }
                }
                chain.proceed()
            }
        }.onFailure { log(TAG, "cannot hook ServiceManager.addService", it) }
    }

    private fun hookActivityManagerReady(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val systemReady = loadClass("com.android.server.am.ActivityManagerService", classLoader).findMethod {
                name == "systemReady"
            }
            deoptimize(module, systemReady)

            var handle: XposedInterface.HookHandle? = null
            handle = systemReady.createHook {
                after { param ->
                    handle?.unhook()
                    // Anything thrown here would abort system_server startup, so it is contained.
                    runCatching {
                        YAMFManager.activityManagerService = param.thisObject
                        YAMFManager.systemReady()
                        log(TAG, "system ready")
                    }.onFailure { log(TAG, "reYAMF failed to initialise", it) }
                }
            }
        }.onFailure { log(TAG, "cannot hook ActivityManagerService.systemReady", it) }
    }

    private fun hookSystemBroadcastChecks(classLoader: ClassLoader) {
        listOf(
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.BroadcastController"
        ).forEach { className ->
            runCatching {
                val check = loadClass(className, classLoader).findMethod {
                    name == "checkBroadcastFromSystem"
                }
                check.createHook {
                    before { param ->
                        val intent = param.args.getOrNull(0) as? Intent
                        if (intent?.action == LauncherHooksConfig.ACTION_RECEIVE_LAUNCHER_CONFIG) {
                            // Same as the legacy hook: swallow the permission check for our broadcast.
                            param.result = Unit
                        }
                    }
                }
            }.onFailure { log(TAG, "$className checkBroadcastFromSystem fail", it) }
        }
    }

    private fun hookInputFocus(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val requestFocus = loadClass("com.android.server.wm.InputMonitor", classLoader).findMethod {
                name == "requestFocus" && parameterCount == 2
            }
            deoptimize(module, requestFocus)

            requestFocus.createHook {
                before { param ->
                    runCatching {
                        YAMFManager.onDisplayFocusChanged(param.thisObject.getObjectAs<Int>("mDisplayId"))
                    }.onFailure { log(TAG, "cannot read the focused display", it) }
                }
            }
        }.onFailure { log(TAG, "cannot hook InputMonitor.requestFocus", it) }
    }

    /**
     * Intercepts the navigation bar's key injection. The three buttons, and the taskbar's copy of
     * them, all send their presses through InputManagerService, so this sees them before the
     * policy hands them to a window on the default display, which is the only place a floating
     * window can claim them from.
     */
    private fun hookInjectedInput(module: XposedModule, classLoader: ClassLoader) {
        val inputManagerService = runCatching {
            loadClass("com.android.server.input.InputManagerService", classLoader)
        }.getOrElse {
            log(TAG, "cannot load InputManagerService", it)
            return
        }
        // Both the binder entry point and the target-specific method it delegates to are hooked:
        // the exact one called differs between releases, and either can be the first to see a key.
        listOf("injectInputEvent", "injectInputEventToTarget").forEach { methodName ->
            runCatching {
                val inject = inputManagerService.findMethod {
                    name == methodName && parameterTypes.isNotEmpty() &&
                            InputEvent::class.java.isAssignableFrom(parameterTypes[0])
                }
                deoptimize(module, inject)
                inject.createHook {
                    before { param ->
                        runCatching {
                            // Claim success: the press was delivered to the window instead.
                            if (NavKeyRouter.onInjectedEvent(param.args.getOrNull(0) as? InputEvent)) {
                                param.result = true
                            }
                        }.onFailure { log(TAG, "cannot route the navigation key", it) }
                    }
                }
                log(TAG, "hooked InputManagerService.$methodName")
            }.onFailure { log(TAG, "cannot hook InputManagerService.$methodName", it) }
        }
    }

    private fun deoptimize(module: XposedModule, executable: Executable) {
        runCatching { module.deoptimize(executable) }
            .onSuccess { if (!it) log(TAG, "deoptimize returned false for $executable") }
            .onFailure { log(TAG, "cannot deoptimize $executable", it) }
    }
}
