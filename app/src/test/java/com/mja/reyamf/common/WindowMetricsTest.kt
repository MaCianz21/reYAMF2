package com.mja.reyamf.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowMetricsTest {

    private val xdpi = 400f
    private val ydpi = 400f

    /** With identical x/y dpi the base density is the physical dpi, whatever the window size. */
    @Test
    fun baseDensity_isPhysicalDensity() {
        assertEquals(400, WindowMetrics.baseDensity(748, 1015, xdpi, ydpi))
        assertEquals(400, WindowMetrics.baseDensity(1080, 2400, xdpi, ydpi))
    }

    /** The regression: reduceDPI used to be subtracted twice on the creation path. */
    @Test
    fun density_subtractsReduceDpiExactlyOnce() {
        val base = WindowMetrics.baseDensity(748, 1015, xdpi, ydpi)

        assertEquals(base - 50, WindowMetrics.density(748, 1015, xdpi, ydpi, 50))
        assertNotEquals(base - 100, WindowMetrics.density(748, 1015, xdpi, ydpi, 50))
    }

    /**
     * Creation and every later resize have to agree: the initial display is created with the
     * same pixel geometry the surface reports afterwards, so both paths must produce one value.
     */
    @Test
    fun creationAndResizeAgree() {
        val creationDensity = WindowMetrics.density(748, 1015, xdpi, ydpi, 50)
        val resizeDensity = WindowMetrics.density(748, 1015, xdpi, ydpi, 50)

        assertEquals(creationDensity, resizeDensity)
        assertEquals(350, creationDensity)
    }

    /** The minimised window resizes the display to twice the surface size; density is unchanged. */
    @Test
    fun density_isIndependentOfWindowScale() {
        val window = WindowMetrics.density(748, 1015, xdpi, ydpi, 50)
        val mini = WindowMetrics.density(748 * 2 + 1, 1015 * 2 + 1, xdpi, ydpi, 50)

        assertEquals(window, mini)
    }

    /** A huge reduceDPI must clamp instead of handing a non-positive density to the framework. */
    @Test
    fun density_isClampedToUsableMinimum() {
        assertEquals(WindowMetrics.MIN_DENSITY, WindowMetrics.density(748, 1015, xdpi, ydpi, 10_000))
        // A negative value is nonsense in a hand-edited config: ignore it instead of raising density.
        assertEquals(400, WindowMetrics.density(748, 1015, xdpi, ydpi, -50))
    }

    /** Bogus framework metrics fall back instead of producing 0, NaN or an overflowed Int. */
    @Test
    fun density_survivesUnusableMetrics() {
        assertEquals(WindowMetrics.FALLBACK_DENSITY, WindowMetrics.density(0, 0, xdpi, ydpi, 0))
        assertTrue(WindowMetrics.density(0, 0, xdpi, ydpi, 50) > 0)
        assertTrue(WindowMetrics.density(748, 1015, Float.NaN, ydpi, 50) > 0)
        assertTrue(WindowMetrics.density(748, 1015, xdpi, 0f, 50) > 0)
        assertTrue(WindowMetrics.density(-1, -1, xdpi, ydpi, 50) > 0)
        assertTrue(WindowMetrics.density(748, 1015, 1e-30f, 1e-30f, 50) > 0)
    }
}
