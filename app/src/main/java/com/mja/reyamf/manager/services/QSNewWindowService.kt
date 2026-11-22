package com.mja.reyamf.manager.services

import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import com.mja.reyamf.manager.applist.AppListWindow

class QSNewWindowService : TileService() {
    companion object {
        private const val TAG = "reYAMF_QSNewWindow"
    }

    override fun onClick() {
        super.onClick()
        // The tile click runs while the app is in the background, so opening the app list
        // window is a service start the system is allowed to refuse.
        runCatching {
            if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("useAppList", true)) {
                YAMFManagerProxy.collapseStatusBarPanel()
                startService(Intent(this, AppListWindow::class.java))
            } else {
                YAMFManagerProxy.createWindow()
            }
        }.onFailure { Log.e(TAG, "cannot open a window from the tile", it) }
    }
}
