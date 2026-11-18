package app.copyphotos.ui

import android.graphics.Bitmap
import android.graphics.Color
import app.copyphotos.core.ThumbnailFit
import kotlin.math.min

/**
 * Whether a square tile should show [bitmap] whole, scaled to fit, instead of its cropped
 * centre: samples the centre square and applies [ThumbnailFit.looksFlat] (e.g. a screenshot of
 * a mostly empty screen would otherwise make a blank-looking tile). The bitmap must be a
 * software bitmap (Coil: allowHardware(false)).
 */
fun cropLooksBlank(bitmap: Bitmap): Boolean {
    val grid = ThumbnailFit.GRID
    val side = min(bitmap.width, bitmap.height)
    if (side < 2) return false
    val left = (bitmap.width - side) / 2
    val top = (bitmap.height - side) / 2
    val lumas = buildList {
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val c = bitmap.getPixel(left + (gx * 2 + 1) * side / (grid * 2), top + (gy * 2 + 1) * side / (grid * 2))
                add(ThumbnailFit.luma(Color.red(c), Color.green(c), Color.blue(c)))
            }
        }
    }
    return ThumbnailFit.looksFlat(lumas)
}
