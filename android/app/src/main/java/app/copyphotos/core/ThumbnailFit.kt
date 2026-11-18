package app.copyphotos.core

import kotlin.math.sqrt

/**
 * Whether a square thumbnail should show the whole image instead of its cropped centre: when
 * that centre is nearly one flat colour (e.g. a screenshot of a mostly empty screen), the
 * cropped tile would look blank. The views sample the centre square on a [GRID] × [GRID] raster.
 */
object ThumbnailFit {
    /** Samples per side of the centre square. */
    const val GRID = 16

    /** Perceived brightness (Rec. 601) of an 8-bit colour. */
    fun luma(red: Int, green: Int, blue: Int): Double = 0.299 * red + 0.587 * green + 0.114 * blue

    /** True when the samples' brightness barely varies (standard deviation below [threshold]). */
    fun looksFlat(lumas: List<Double>, threshold: Double = 6.0): Boolean {
        if (lumas.isEmpty()) return false
        val mean = lumas.average()
        val variance = lumas.sumOf { it * it } / lumas.size - mean * mean
        return sqrt(maxOf(0.0, variance)) < threshold
    }
}
