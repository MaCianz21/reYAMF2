package com.mja.reyamf.common

import kotlin.math.floor
import kotlin.math.hypot

/**
 * Geometry of the floating window's virtual display.
 *
 * A window is a virtual display whose density is the physical screen density minus
 * [com.mja.reyamf.common.model.Config.reduceDPI]. That reduction used to be subtracted twice on
 * the creation path and once on every resize, so an app launched while the window was opening
 * got a much smaller density than the one it received as soon as the surface arrived, and was
 * restarted and rescaled. Every creation/resize path now goes through [density], which applies
 * the setting exactly once.
 */
object WindowMetrics {
    /** Lowest density handed to the display manager; it rejects non-positive values. */
    const val MIN_DENSITY = 72

    /** Density used when the caller's metrics are unusable (zero size, bogus dpi). */
    const val FALLBACK_DENSITY = 160

    /**
     * Density a screen of [widthPx] x [heightPx] pixels with the given physical dpi would have,
     * i.e. the density before the user's reduction is applied.
     */
    fun baseDensity(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float): Int {
        if (widthPx <= 0 || heightPx <= 0) return FALLBACK_DENSITY
        if (!xdpi.isFinite() || !ydpi.isFinite() || xdpi <= 0f || ydpi <= 0f) return FALLBACK_DENSITY

        val inches = hypot(widthPx / xdpi.toDouble(), heightPx / ydpi.toDouble())
        if (!inches.isFinite() || inches <= 0.0) return FALLBACK_DENSITY

        val diagonalPixels = hypot(widthPx.toDouble(), heightPx.toDouble())
        val density = floor(diagonalPixels / inches)
        if (!density.isFinite() || density < 1.0 || density > Int.MAX_VALUE.toDouble()) {
            return FALLBACK_DENSITY
        }
        return density.toInt()
    }

    /**
     * Density for a virtual display of [widthPx] x [heightPx] pixels. [reduceDpi] is subtracted
     * exactly once, and the result is clamped so the display manager always receives a usable
     * value.
     */
    fun density(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float, reduceDpi: Int): Int {
        val base = baseDensity(widthPx, heightPx, xdpi, ydpi)
        return (base - reduceDpi.coerceAtLeast(0)).coerceAtLeast(MIN_DENSITY)
    }

    /**
     * Lowest gravity offset that keeps a window's top edge at [topInsetPx] on screen.
     *
     * The floating window is laid out with FLAG_LAYOUT_NO_LIMITS, so nothing but this clamp stops
     * its top edge from sliding over the status bar, where the notch that moves it cannot be
     * grabbed any more. The offset is derived from the window's measured position instead of from
     * the screen size: the window manager lays overlays out inside an area it has already inset by
     * the bars, so an offset computed from the full screen height leaves the top edge exactly that
     * far under the status bar. [topAtZeroOffset] is the top edge's measured screen position when
     * the offset is zero, which makes this a plain delta that works whatever the gravity.
     */
    fun minGravityY(topAtZeroOffset: Int, topInsetPx: Int): Int =
        topInsetPx.coerceAtLeast(0) - topAtZeroOffset

    /**
     * Clamps a gravity offset so the top edge stays below [topInsetPx] and the bottom edge stays on
     * screen. A window taller than the screen keeps its top edge visible, because that is the part
     * the user drags it by.
     */
    fun clampGravityY(
        currentY: Int,
        topAtZeroOffset: Int,
        windowHeightPx: Int,
        screenHeightPx: Int,
        topInsetPx: Int
    ): Int {
        val minY = minGravityY(topAtZeroOffset, topInsetPx)
        val maxY = (screenHeightPx - windowHeightPx - topAtZeroOffset).coerceAtLeast(minY)
        return currentY.coerceIn(minY, maxY)
    }
}
