package app.copyphotos.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.copyphotos.R
import app.copyphotos.core.CameraMetadata
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Camera metadata as text in the user's locale, the way Google Photos' Details panel shows it
 * ("ƒ/1.78 · 1/120 · 6.77 mm · ISO 80", "12 MP · 4K"). The rules come from [CameraMetadata];
 * numbers, lengths and durations are formatted by the system, and the unit words come from
 * strings.xml.
 */
object MetadataText {
    @Composable
    private fun locale(): Locale = LocalConfiguration.current.locales[0]

    /** Up to [decimals] decimals, without trailing zeros. */
    @Composable
    private fun number(value: Double, decimals: Int): String =
        NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = decimals }.format(value)

    @Composable
    fun exposureTime(seconds: Double): String? = when (val s = CameraMetadata.shutterSpeed(seconds)) {
        is CameraMetadata.ShutterSpeed.Fraction -> stringResource(R.string.metadata_shutter_fraction, s.denominator)
        is CameraMetadata.ShutterSpeed.Seconds -> stringResource(R.string.metadata_shutter_seconds, number(s.seconds, 1))
        null -> null
    }

    @Composable
    fun fNumber(value: Double): String? =
        if (value.isFinite() && value > 0) stringResource(R.string.metadata_f_number, number(value, 2)) else null

    @Composable
    fun focalLength(mm: Double): String? {
        if (!mm.isFinite() || mm <= 0) return null
        val numbers = android.icu.text.NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = 2 }
        return MeasureFormat.getInstance(locale(), MeasureFormat.FormatWidth.SHORT, numbers)
            .format(Measure(mm, MeasureUnit.MILLIMETER))
    }

    @Composable
    fun iso(value: Int): String? = if (value > 0) stringResource(R.string.metadata_iso, value) else null

    @Composable
    fun exposureBias(ev: Double): String? {
        val rounded = CameraMetadata.exposureBias(ev) ?: return null
        val signed = (NumberFormat.getNumberInstance(locale()) as DecimalFormat).apply {
            maximumFractionDigits = 1
            if (rounded != 0.0) positivePrefix = "+$positivePrefix"
        }
        return stringResource(R.string.metadata_exposure_bias, signed.format(rounded))
    }

    @Composable
    fun megapixels(width: Int, height: Int): String? =
        CameraMetadata.megapixels(width, height)?.let { stringResource(R.string.metadata_megapixels, number(it, 1)) }

    @Composable
    fun videoResolution(width: Int, height: Int): String? = when (val r = CameraMetadata.videoResolution(width, height)) {
        CameraMetadata.VideoResolution.Uhd8K -> stringResource(R.string.metadata_8k)
        CameraMetadata.VideoResolution.Uhd4K -> stringResource(R.string.metadata_4k)
        is CameraMetadata.VideoResolution.Lines -> stringResource(R.string.metadata_lines, r.lines)
        null -> null
    }

    @Composable
    fun frameRate(fps: Double): String? =
        if (fps.isFinite() && fps > 0) stringResource(R.string.metadata_frame_rate, number(fps, 2)) else null

    /** "0:07", "12:03", "1:02:03". */
    @Composable
    fun duration(millis: Long): String? {
        if (millis < 0) return null
        val total = (millis + 500) / 1000
        val parts = buildList {
            if (total >= 3600) add(Measure(total / 3600, MeasureUnit.HOUR))
            add(Measure(total % 3600 / 60, MeasureUnit.MINUTE))
            add(Measure(total % 60, MeasureUnit.SECOND))
        }
        return MeasureFormat.getInstance(locale(), MeasureFormat.FormatWidth.NUMERIC).formatMeasures(*parts.toTypedArray())
    }

    /** "48.85840, 2.29450", in decimal degrees. */
    @Composable
    fun coordinate(latitude: Double, longitude: Double): String {
        val numbers = NumberFormat.getNumberInstance(locale()).apply {
            minimumFractionDigits = 5
            maximumFractionDigits = 5
        }
        return stringResource(R.string.metadata_coordinate, numbers.format(latitude), numbers.format(longitude))
    }
}
