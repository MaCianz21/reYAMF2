package com.mja.reyamf.common

/**
 * Bookkeeping of the open floating windows, in stacking order (index 0 is on top).
 *
 * Kept free of Android types so the counter/ordering rules can be unit tested on the JVM. The
 * previous bookkeeping kept a list and a separate counter that could drift apart (a window that
 * failed to create, or a double removal, left the count permanently wrong).
 */
class WindowRegistry {
    private val lock = Any()
    private val windows = mutableListOf<Int>()

    val count: Int
        get() = synchronized(lock) { windows.size }

    val ids: List<Int>
        get() = synchronized(lock) { windows.toList() }

    /**
     * Adds [id] on top of the stack. Adding an id that is already tracked only moves it to the
     * top, so a duplicate registration cannot inflate the open-window count.
     */
    fun add(id: Int): Int = synchronized(lock) {
        windows.remove(id)
        windows.add(0, id)
        windows.size
    }

    /** Removes [id]; returns true when it was tracked. */
    fun remove(id: Int): Boolean = synchronized(lock) { windows.remove(id) }

    /** The first window is on top; with no windows open every id counts as top. */
    fun isTop(id: Int): Boolean = synchronized(lock) {
        windows.isEmpty() || windows[0] == id
    }

    /** Moves a tracked window to the top; unknown ids are ignored. */
    fun moveToTop(id: Int) {
        synchronized(lock) {
            if (windows.remove(id)) windows.add(0, id)
        }
    }
}
