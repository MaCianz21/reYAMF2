package com.mja.reyamf.xposed.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.content.Context
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.os.ServiceManager
import android.os.UserManager
import android.view.IWindowManager
import android.view.WindowManager
import com.android.internal.statusbar.IStatusBarService
import com.mja.reyamf.common.reflect.getObjectAs

@SuppressLint("StaticFieldLeak")
object Instances {
    private const val TAG = "reYAMF_Instances"

    lateinit var windowManager: WindowManager
        private set
    lateinit var iWindowManager: IWindowManager
        private set
    lateinit var inputManager: IInputManager
        private set
    lateinit var displayManager: DisplayManager
        private set
    lateinit var activityTaskManager: IActivityTaskManager
        private set
    lateinit var packageManager: PackageManager
        private set
    lateinit var activityManager: ActivityManager
        private set
    lateinit var userManager: UserManager
        private set
    lateinit var iPackageManager: IPackageManager
        private set
    lateinit var iStatusBarService: IStatusBarService
        private set
    private lateinit var activityManagerService: Any
        private set
    lateinit var systemContext: Context
        private set

    /**
     * Newer system_server builds do not guarantee mUiContext, so fall back to the AMS context.
     */
    val systemUiContext: Context
        get() = runCatching { activityManagerService.getObjectAs<Context>("mUiContext") }
            .getOrNull() ?: systemContext

    fun init(activityManagerService: Any) {
        Instances.activityManagerService = activityManagerService
        systemContext = activityManagerService.getObjectAs("mContext")

        windowManager = systemContext.getSystemService(WindowManager::class.java)
        displayManager = systemContext.getSystemService(DisplayManager::class.java)
        packageManager = systemContext.packageManager
        activityManager = systemContext.getSystemService(ActivityManager::class.java)
        userManager = systemContext.getSystemService(UserManager::class.java)

        // Each lookup is optional: one renamed service must not abort the whole
        // initialisation and leave the module half-initialised.
        runCatching { IWindowManager.Stub.asInterface(ServiceManager.getService("window")) }
            .onSuccess { iWindowManager = it }
            .onFailure { log(TAG, "window service unavailable", it) }
        runCatching { IInputManager.Stub.asInterface(ServiceManager.getService("input")) }
            .onSuccess { inputManager = it }
            .onFailure { log(TAG, "input service unavailable", it) }
        runCatching {
            IActivityTaskManager.Stub.asInterface(ServiceManager.getService("activity_task"))
        }.onSuccess { activityTaskManager = it }
            .onFailure { log(TAG, "activity_task service unavailable", it) }
        runCatching { IPackageManager.Stub.asInterface(ServiceManager.getService("package")) }
            .onSuccess { iPackageManager = it }
            .onFailure { log(TAG, "package service unavailable", it) }
        runCatching { IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar")) }
            .onSuccess { iStatusBarService = it }
            .onFailure { log(TAG, "statusbar service unavailable", it) }
    }
}
