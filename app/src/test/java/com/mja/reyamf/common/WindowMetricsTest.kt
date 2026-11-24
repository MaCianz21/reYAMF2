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

    /**
     * The device regression: offsets are measured from a window manager area that is already
     * inset by the bars, so the clamp has to work from the window's measured position.
     */
    @Test
    fun minGravityY_pullsTheTopEdgeToTheStatusBar() {
        // Tablet: at offset 0 the top edge sits at screen y=671, behind a 42px status bar.
        assertEquals(-629, WindowMetrics.minGravityY(topAtZeroOffset = 671, topInsetPx = 42))
        // No bar, so the top edge may reach screen y=0.
        assertEquals(-671, WindowMetrics.minGravityY(topAtZeroOffset = 671, topInsetPx = 0))
    }

    /** A window whose top edge is above the bar at offset 0 is pushed back down to it. */
    @Test
    fun minGravityY_pushesAWindowThatSitsAboveTheBar() {
        assertEquals(142, WindowMetrics.minGravityY(topAtZeroOffset = -100, topInsetPx = 42))
    }

    /** A nonsense negative inset must not move the window. */
    @Test
    fun minGravityY_ignoresNegativeInset() {
        assertEquals(-671, WindowMetrics.minGravityY(topAtZeroOffset = 671, topInsetPx = -50))
    }

    /** A window inside the bars keeps its offset; one dragged up stops at the status bar. */
    @Test
    fun clampGravityY_keepsTheTopEdgeBelowTheStatusBar() {
        assertEquals(-120, WindowMetrics.clampGravityY(-120, 671, 574, 2000, 42))
        assertEquals(-629, WindowMetrics.clampGravityY(-900, 671, 574, 2000, 42))
    }

    /** The bottom edge is still kept on screen, as before. */
    @Test
    fun clampGravityY_keepsBottomEdgeOnScreen() {
        assertEquals(1526, WindowMetrics.clampGravityY(2000, -100, 574, 2000, 42))
    }

    /** A window taller than the screen keeps the edge the user drags it by reachable. */
    @Test
    fun clampGravityY_prefersTopEdgeForTallWindows() {
        assertEquals(-858, WindowMetrics.clampGravityY(0, 900, 2000, 2000, 42))
    }
}
