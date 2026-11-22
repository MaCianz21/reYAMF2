package com.mja.reyamf.common

import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

val gson by lazy { Gson() }

fun RecyclerView.resetAdapter() {
    this.adapter = adapter
}

inline fun <T> Result<T>.onException(action: (exception: Exception) -> Unit): Result<T> =
    this.onFailure { t ->
        if (t is Error) throw t
        action(t as Exception)
    }

/**
 * The module code runs inside system_server. An exception escaping a coroutine there
 * would restart the whole system UI, so every failure is reported to the log instead.
 */
private val globalExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e("reYAMF", "Uncaught coroutine failure", throwable)
}

@OptIn(DelicateCoroutinesApi::class)
fun runMain(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Main + globalExceptionHandler, block = block)

@OptIn(DelicateCoroutinesApi::class)
fun runIO(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.IO + globalExceptionHandler, block = block)

fun Resources.Theme.getAttr(@AttrRes id: Int) =
    TypedValue().apply { resolveAttribute(id, this, true) }
