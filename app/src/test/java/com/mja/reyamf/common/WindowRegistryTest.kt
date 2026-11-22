package com.mja.reyamf.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowRegistryTest {

    @Test
    fun add_returnsOpenWindowCount() {
        val registry = WindowRegistry()

        assertEquals(1, registry.add(11))
        assertEquals(2, registry.add(22))
        assertEquals(2, registry.count)
    }

    /** The same display must not be counted twice (double "open" from two broadcast sources). */
    @Test
    fun add_duplicateIdDoesNotInflateCount() {
        val registry = WindowRegistry()
        registry.add(11)
        registry.add(22)

        assertEquals(2, registry.add(11))
        assertEquals(2, registry.count)
        assertEquals(listOf(11, 22), registry.ids)
    }

    @Test
    fun remove_tracksCountAndRejectsUnknownIds() {
        val registry = WindowRegistry()
        registry.add(11)
        registry.add(22)

        assertTrue(registry.remove(22))
        assertEquals(1, registry.count)
        assertFalse(registry.remove(22))
        assertEquals(1, registry.count)
    }

    @Test
    fun remove_allWindowsEndsAtZero() {
        val registry = WindowRegistry()
        registry.add(1)
        registry.remove(1)

        assertEquals(0, registry.count)
        assertTrue(registry.isTop(42))
    }

    @Test
    fun isTop_followsStackingOrder() {
        val registry = WindowRegistry()
        registry.add(11)
        registry.add(22)

        assertTrue(registry.isTop(22))
        assertFalse(registry.isTop(11))

        registry.moveToTop(11)
        assertTrue(registry.isTop(11))
        assertEquals(listOf(11, 22), registry.ids)
    }

    @Test
    fun moveToTop_ignoresUnknownIds() {
        val registry = WindowRegistry()
        registry.add(11)

        registry.moveToTop(99)

        assertEquals(listOf(11), registry.ids)
        assertEquals(1, registry.count)
    }
}
