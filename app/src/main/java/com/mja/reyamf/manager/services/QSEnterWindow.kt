package com.mja.reyamf.manager.services

import android.service.quicksettings.TileService
import android.util.Log

class QSEnterWindow: TileService() {
    companion object {
        private const val TAG = "reYAMF_QSEnterWindow"
    }

    override fun onClick() {
        super.onClick()
        runCatching { YAMFManagerProxy.currentToWindow() }
            .onFailure { Log.e(TAG, "cannot windowfy the current task", it) }
    }
}
