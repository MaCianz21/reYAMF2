package com.mja.reyamf.xposed.utils

import com.mja.reyamf.common.runMain
import kotlinx.coroutines.CoroutineScope
import java.util.LinkedList

object RunMainThreadQueue {
    private const val TAG = "reYAMF_RunMainThreadQueue"

    private val queue = LinkedList<suspend CoroutineScope.() -> Unit>()

    @Synchronized
    fun add(run: suspend CoroutineScope.() -> Unit) {
        queue.offer(run)
        if (queue.size != 1) return
        runMain {
            while (true) {
                val next = synchronized(this@RunMainThreadQueue) { queue.poll() } ?: break
                // One failing task must not strand the rest of the queue.
                runCatching { next.invoke(this) }.onFailure {
                    log(TAG, "queued task failed", it)
                }
            }
        }
    }
}
