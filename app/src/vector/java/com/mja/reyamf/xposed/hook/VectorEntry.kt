package com.mja.reyamf.xposed.hook

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.HookPlatform
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern libxposed entry point (Vector 2.1+ / API 101).
 *
 * One entry class handles both targets: system_server through [onSystemServerStarting] and the
 * launcher through [onPackageReady]. Registration lives in META-INF/xposed/java_init.list.
 */
class VectorEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        EzXposed.initOnModuleLoaded(this, param)

        // Shared module code keeps talking to the same seam, whichever framework is loaded.
        HookPlatform.logSink = { priority, tag, message, t ->
            if (t != null) log(priority, tag, message, t) else log(priority, tag, message)
        }
        HookPlatform.applicationProvider = { runCatching { EzXposed.appContext }.getOrNull() }
        HookPlatform.moduleAssetPathAdder = { resources -> EzXposed.addModuleAssetPath(resources) }

        runCatching { EzXposed.initModuleResources() }
            .onFailure { log(Log.ERROR, TAG, "cannot load the module resources", it) }

        log(
            Log.INFO,
            TAG,
            "reYAMF ${BuildConfig.VERSION_NAME} loaded into ${param.processName} " +
                "(systemServer=${param.isSystemServer}, apiVersion=$apiVersion)"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        HookSystem.install(this, param.classLoader)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName in LauncherHooksConfig.LAUNCHER_PACKAGES) {
            EzXposed.initOnPackageLoaded(param)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // The module is only installed into the scoped packages, but a process may host more than
        // one, so the launcher hooks are still gated on the package name.
        if (param.packageName !in LauncherHooksConfig.LAUNCHER_PACKAGES) return
        EzXposed.initOnPackageReady(param)
        HookLauncher.install(param.packageName, param.classLoader)
    }

    private companion object {
        const val TAG = "reYAMF_Vector"
    }
}
