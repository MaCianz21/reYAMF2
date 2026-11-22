package com.mja.reyamf.manager.services

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.util.Log
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.IOpenCountListener
import com.mja.reyamf.xposed.IYAMFManager

object YAMFManagerProxy : IYAMFManager, DeathRecipient {
    private const val TAG = "reYAMFManagerProxy"

    @Volatile
    private var service: IYAMFManager? = null

    /**
     * The service lives in system_server, which can restart at any moment. A dead binder must
     * degrade to the fallback instead of crashing the manager UI.
     */
    private fun <T> call(fallback: T, block: (IYAMFManager) -> T): T {
        val current = service ?: return fallback
        return runCatching { block(current) }
            .onFailure { t ->
                Log.w(TAG, "service call failed", t)
                if (t is DeadObjectException) service = null
            }
            .getOrDefault(fallback)
    }

    fun linkService(binder: IBinder) {
        service = IYAMFManager.Stub.asInterface(binder)
        runCatching { binder.linkToDeath(this, 0) }
            .onFailure { Log.w(TAG, "cannot watch the module service", it) }
    }

    override fun binderDied() {
        service = null
        Log.e(TAG, "Binder died")
    }

    override fun asBinder() = service?.asBinder()

    override fun getVersionName(): String? = call(null) { it.versionName }

    override fun getVersionCode() = call(0) { it.versionCode }

    override fun getUid() = call(-1) { it.uid }

    override fun createWindow() = call(Unit) { it.createWindow() }

    override fun getBuildTime(): Long = call(0L) { it.buildTime }

    override fun getConfigJson(): String = call("{}") { it.configJson }

    override fun updateConfig(newConfig: String) = call(Unit) { it.updateConfig(newConfig) }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) =
        call(Unit) { it.registerOpenCountListener(iOpenCountListener) }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener) =
        call(Unit) { it.unregisterOpenCountListener(iOpenCountListener) }

    override fun currentToWindow() = call(Unit) { it.currentToWindow() }

    override fun resetAllWindow() = call(Unit) { it.resetAllWindow() }

    override fun getAppList(): List<AppInfo?>? = call(emptyList()) { it.appList }

    override fun createWindowUserspace(appInfo: AppInfo?) =
        call(Unit) { it.createWindowUserspace(appInfo) }

    override fun getAppListAsync(callback: IAppListCallback) =
        call(Unit) { it.getAppListAsync(callback) }

    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) =
        call(Unit) { it.getAppIcon(callback, appInfo) }

    override fun collapseStatusBarPanel() = call(Unit) { it.collapseStatusBarPanel() }
}
