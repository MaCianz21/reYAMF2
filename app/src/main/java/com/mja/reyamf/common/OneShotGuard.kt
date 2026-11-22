package com.mja.reyamf.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a block at most once.
 *
 * Teardown paths are reachable several times for the same object: a floating window can be
 * closed by its button, by task removal and by the task-stack listener, and a sidebar service can
 * be stopped by the user and by the system. Removing views or unregistering receivers twice
 * throws, so the second call has to become a no-op.
 */
class OneShotGuard {
    private val used = AtomicBoolean(false)

    val isUsed: Boolean
        get() = used.get()

    /**
     * Runs [block] only on the first call and returns true when it ran. The guard is marked as
     * used before the block runs, so a throwing block is not retried in a loop; the exception is
     * still propagated to the caller, which is expected to contain it.
     */
    fun runOnce(block: () -> Unit): Boolean {
        if (!used.compareAndSet(false, true)) return false
        block()
        return true
    }
}
