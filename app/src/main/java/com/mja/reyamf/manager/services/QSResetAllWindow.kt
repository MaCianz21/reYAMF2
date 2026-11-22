package com.mja.reyamf.manager.services

import android.service.quicksettings.TileService
import android.util.Log

class QSResetAllWindow: TileService() {
    companion object {
        private const val TAG = "reYAMF_QSResetAllWindow"
    }

    override fun onClick() {
        super.onClick()
        runCatching { YAMFManagerProxy.resetAllWindow() }
            .onFailure { Log.e(TAG, "cannot reset the windows", it) }
    }
}
