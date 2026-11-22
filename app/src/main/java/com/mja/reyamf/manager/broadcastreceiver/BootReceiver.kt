package com.mja.reyamf.manager.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.manager.sidebar.SidebarService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "reYAMFBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = runCatching { gson.fromJson(YAMFManagerProxy.configJson, Config::class.java) }
            .onFailure { Log.w(TAG, "cannot read the config at boot", it) }
            .getOrNull() ?: return

        if (!config.launchSideBarAtBoot) return

        // A background service start at boot can be refused; that must not abort the receiver.
        runCatching { context.startService(Intent(context, SidebarService::class.java)) }
            .onFailure { Log.e(TAG, "cannot start the sidebar at boot", it) }
    }
}
