package com.mja.reyamf.manager.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.mja.reyamf.manager.services.YAMFManagerProxy

class ServiceProvider: ContentProvider() {
    companion object {
        private const val TAG = "reYAMFServiceProvider"
    }

    override fun onCreate() = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun getType(uri: Uri) = null

    override fun insert(uri: Uri, values: ContentValues?) = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ) = 0

    /**
     * Called by system_server to hand the module service to the app. This runs on the caller's
     * side of the process boundary, so a failure must be answered with null rather than thrown.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null
        return runCatching {
            if (callingPackage != "android") return@runCatching null
            val binder = extras.getBinder("binder") ?: return@runCatching null
            YAMFManagerProxy.linkService(binder)
            Bundle()
        }.onFailure {
            Log.w(TAG, "cannot accept the module service", it)
        }.getOrNull()
    }
}
