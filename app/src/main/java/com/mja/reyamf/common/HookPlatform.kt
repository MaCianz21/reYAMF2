package com.mja.reyamf.common

import android.content.Context
import android.content.res.Resources
import android.util.Log

/**
 * Seam between the shared module code and whichever hook framework was compiled in.
 *
 * The legacy Xposed API hands modules static helpers (XposedBridge, AndroidAppHelper, EzXHelper's
 * module resources) while the modern libxposed API only hands out the module object. Each entry
 * point installs these callbacks once it is loaded, so code outside the hook packages does not
 * need to know which framework is running.
 */
object HookPlatform {
    @Volatile
    var logSink: ((Int, String, String, Throwable?) -> Unit)? = null

    @Volatile
    var moduleAssetPathAdder: ((Resources) -> Unit)? = null

    @Volatile
    var applicationProvider: (() -> Context?)? = null

    fun log(priority: Int, tag: String, message: String, t: Throwable? = null) {
        val sink = logSink
        if (sink != null && runCatching { sink(priority, tag, message, t) }.isSuccess) return
        runCatching {
            Log.println(priority, tag, message)
            if (t != null) Log.println(Log.ERROR, tag, Log.getStackTraceString(t))
        }
    }

    fun addModuleAssetPath(resources: Resources) {
        runCatching { moduleAssetPathAdder?.invoke(resources) }
    }

    fun currentApplication(): Context? =
        runCatching { applicationProvider?.invoke() }.getOrNull()
}
