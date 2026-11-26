package com.mja.reyamf.xposed.hook

import android.content.Intent
import android.content.pm.IPackageManager
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import com.mja.reyamf.common.reflect.findMethod
import com.mja.reyamf.common.reflect.getObjectAs
import com.mja.reyamf.common.reflect.loadClass
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.xposed.services.ImeVisibilityBridge
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
        hookImeVisibility(module, classLoader)
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

    /**
     * Keeps the tablet's own keyboard up for an app on a window's display.
     *
     * The fallback IME policy draws the keyboard on the tablet, but the framework reads the app's
     * request back through the app display's own IME insets source, which a fallback display does
     * not have: the request counts as withdrawn and the keyboard is hidden again right after being
     * drawn. These hooks keep that request true while [ImeVisibilityBridge] holds a window's
     * keyboard, and let the app's own hide request and the back press close it.
     */
    private fun hookImeVisibility(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val immService =
                loadClass("com.android.server.inputmethod.InputMethodManagerService", classLoader)
            // Every show and hide entry point is hooked, every overload: the server shows the
            // keyboard on its own when the app's input target gains focus, only some paths carry a
            // window token, and the token is not always the first parameter.
            val showMethods = ImeVisibilityBridge.SHOW_METHODS
            val hideMethods = ImeVisibilityBridge.HIDE_METHODS
            (showMethods + hideMethods).forEach { methodName ->
                immService.declaredMethods.filter { it.name == methodName }.forEach { method ->
                    runCatching {
                        deoptimize(module, method)
                        if (methodName in hideMethods) {
                            ImeVisibilityBridge.rememberHideMethod(method)
                        }
                        if (methodName in showMethods) {
                            ImeVisibilityBridge.rememberShowMethod(method)
                        }
                        method.createHook {
                            before { param ->
                                runCatching {
                                    ImeVisibilityBridge.attachService(param.thisObject)
                                    when {
                                        methodName in showMethods -> {
                                            val token =
                                                param.args.firstOrNull { it is IBinder } as? IBinder
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

                                        // The app itself is done typing.
                                        methodName == ImeVisibilityBridge.CLIENT_HIDE ->
                                            ImeVisibilityBridge.onHideRequested()

                                        // The server hides the keyboard because it believes
                                        // the app withdrew the request, which is the
                                        // misunderstanding these hooks exist for.
                                        ImeVisibilityBridge.keepsTabletKeyboard ->
                                            param.result =
                                                ImeVisibilityBridge.skippedHideResult(method)
                                    }
                                }.onFailure { Log.e(TAG, "cannot track $methodName", it) }
                            }
                        }
                    }.onFailure { Log.e(TAG, "cannot hook $method", it) }
                }
            }
            Log.i(TAG, "hooked the keyboard requests of InputMethodManagerService")
        }.onFailure { Log.e(TAG, "cannot load InputMethodManagerService", it) }

        listOf(
            "com.android.server.wm.DisplayContent\$RemoteInsetsControlTarget",
            "com.android.server.wm.WindowState"
        ).forEach { className ->
            runCatching {
                val method = loadClass(className, classLoader).findMethod {
                    name == "setImeInputTargetRequestedVisibility" && parameterCount >= 1
                }
                deoptimize(module, method)
                method.createHook {
                    before { param ->
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
                            // A first request starts the keep, and every later withdrawal for the
                            // same display is overridden.
                            param.args[0] = ImeVisibilityBridge.imeRequestedValue(
                                requested,
                                param.args.getOrNull(1),
                                param.thisObject
                            )
                        }.onFailure { Log.e(TAG, "cannot keep the IME requested", it) }
                    }
                }
                Log.i(TAG, "hooked $className.setImeInputTargetRequestedVisibility")
            }.onFailure {
                Log.e(TAG, "cannot hook $className.setImeInputTargetRequestedVisibility", it)
            }
        }

        runCatching {
            val method = loadClass("com.android.server.wm.ImeInsetsSourceProvider", classLoader)
                .findMethod { name == "invokeOnImeRequestedChangedListener" }
            deoptimize(module, method)
            method.createHook {
                before { param ->
                    runCatching {
                        // The listener would take the withdrawn request to the input method
                        // service, but it carries the app's show as well: only a withdrawal is
                        // dropped while a window keeps the keyboard.
                        if (ImeVisibilityBridge.keepsTabletKeyboard &&
                            ImeVisibilityBridge.imeChangeWantsHide(param.args.getOrNull(0))
                        ) {
                            param.result = Unit
                        }
                    }.onFailure { Log.e(TAG, "cannot skip the IME change listener", it) }
                }
            }
            Log.i(TAG, "hooked ImeInsetsSourceProvider.invokeOnImeRequestedChangedListener")
        }.onFailure { Log.e(TAG, "cannot hook ImeInsetsSourceProvider", it) }
    }

    private fun deoptimize(module: XposedModule, executable: Executable) {
        runCatching { module.deoptimize(executable) }
            .onSuccess { if (!it) log(TAG, "deoptimize returned false for $executable") }
            .onFailure { log(TAG, "cannot deoptimize $executable", it) }
    }
}
