package app.copyphotos.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * How camera metadata is read, rounded and classified, as numbers and cases. The views format
 * the results for the user's locale ("ƒ/1.78 · 1/120 · 6.77 mm · ISO 80", "12 MP · 4K"). Pure,
 * so it is unit-tested. (iOS has the same rules; it also splits coordinates into degrees,
 * minutes and seconds, which Android shows as decimal degrees instead.)
 */
object CameraMetadata {
    sealed class ShutterSpeed {
        /** 1/[denominator] of a second. */
        data class Fraction(val denominator: Long) : ShutterSpeed()
        /** Whole seconds, rounded to one decimal. */
        data class Seconds(val seconds: Double) : ShutterSpeed()
    }

    /** A fraction up to half a second; longer ones in seconds (0.8 s as a fraction would round to a misleading 1/1). */
    fun shutterSpeed(seconds: Double): ShutterSpeed? {
        if (!seconds.isFinite() || seconds <= 0) return null
        return if (seconds <= 0.5) ShutterSpeed.Fraction((1 / seconds).roundToLong())
        else ShutterSpeed.Seconds((seconds * 10).roundToLong() / 10.0)
    }

    /** Exposure compensation, rounded to 0.1 EV. */
    fun exposureBias(ev: Double): Double? =
        if (ev.isFinite()) ((ev * 10).roundToLong() / 10.0).let { if (it == 0.0) 0.0 else it } else null

    /** Megapixels: whole once there are enough of them (12.2 → 12), else one decimal (8.3). */
    fun megapixels(width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0) return null
        val mp = width.toDouble() * height / 1_000_000
        return if (mp >= 10) mp.roundToLong().toDouble() else (mp * 10).roundToLong() / 10.0
    }

    sealed class VideoResolution {
        data object Uhd8K : VideoResolution()
        data object Uhd4K : VideoResolution()
        /** "1080p", "720p": the short side in lines. */
        data class Lines(val lines: Int) : VideoResolution()
    }

    fun videoResolution(width: Int, height: Int): VideoResolution? {
        val short = min(width, height)
        val long = max(width, height)
        if (short <= 0) return null
        return when {
            long >= 7680 || short >= 4320 -> VideoResolution.Uhd8K
            long >= 3840 || short >= 2160 -> VideoResolution.Uhd4K
            else -> VideoResolution.Lines(short)
        }
    }

    /**
     * The EXIF OffsetTime string as seconds from GMT.
     * @param exif the string: "+02:00", "-05:30" or "Z".
     * @return the offset, or null if the string isn't a valid offset.
     */
    fun timeZoneOffset(exif: String?): Int? {
        val s = exif?.trim() ?: return null
        if (s == "Z") return 0
        val (sign, hh, mm) = OFFSET.matchEntire(s)?.destructured ?: return null
        val h = hh.toInt()
        val m = mm.toInt()
        if (h > 18 || m >= 60) return null
        return (if (sign == "-") -1 else 1) * (h * 3600 + m * 60)
    }

    /** "+02:00" or "-05:30": sign, hours, minutes. */
    private val OFFSET = Regex("""([+-])(\d\d):(\d\d)""")

    /**
     * EXIF DateTimeOriginal ("2012:08:08 14:29:49") as epoch millis. With no EXIF offset the
     * time is read as local time in [defaultZone], as Photos apps do.
     */
    fun parseExifDateTime(value: String?, offsetSeconds: Int?, defaultZone: ZoneId): Long? {
        val s = value?.trim()?.takeIf { it.length >= 19 } ?: return null
        val local = runCatching {
            LocalDateTime.parse(s.substring(0, 19), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        }.getOrNull() ?: return null
        val zone = offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it) } ?: defaultZone
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** ISO 6709 location from video metadata, e.g. "+48.8584+002.2945/" or "+37.3317-122.0310+012.5/". */
    fun parseIso6709(value: String?): Pair<Double, Double>? {
        val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value?.trim() ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (abs(lat) > 90 || abs(lon) > 180 || (lat == 0.0 && lon == 0.0)) return null
        return lat to lon
    }

    /** "video/avc" → "H.264": the codec's technical name, the same in every language. */
    fun codecName(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc", "video/dolby-vision" -> "HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        else -> mime.substringAfter('/').uppercase()
    }

    /** The short names of well-known MIME types. */
    private val FORMAT_NAMES = mapOf(
        "image/jpeg" to "JPEG", "image/heic" to "HEIF", "image/heif" to "HEIF", "image/png" to "PNG",
        "image/webp" to "WebP", "image/gif" to "GIF", "image/avif" to "AVIF", "image/x-adobe-dng" to "RAW",
        "image/dng" to "RAW", "video/mp4" to "MP4", "video/quicktime" to "MOV", "video/webm" to "WebM",
        "video/3gpp" to "3GP",
    )

    /**
     * The short name of a file's format, for the format badge: a technical name, the same in every language.
     * @param mime the file's MIME type.
     * @param fileName the file's name, whose extension is used for unknown types.
     * @return the name ("JPEG"), or null if neither the type nor the extension says.
     */
    fun formatName(mime: String?, fileName: String): String? =
        FORMAT_NAMES[mime?.lowercase()] ?: fileName.substringAfterLast('.', "").uppercase().ifEmpty { null }

    /**
     * "Google Pixel 8": the make is left out when the model already starts with it, and starts
     * with a capital (some makers write "samsung").
     */
    fun cameraName(make: String?, model: String?): String? {
        fun clean(s: String?) = s?.trim { it.isWhitespace() || it.isISOControl() }?.takeIf { it.isNotEmpty() }
        val mk = clean(make)?.replaceFirstChar { it.uppercaseChar() }
        val md = clean(model)
        return when {
            mk != null && md != null && md.lowercase().startsWith(mk.lowercase()) -> md
            mk != null && md != null -> "$mk $md"
            else -> md ?: mk
        }
    }

    /**
     * Which parts of a reverse-geocoded address name a photo's place, most specific first: the
     * town (or else its district), then the region (or else the country). With neither, the
     * place's own name (a landmark, or the sea). The views join the parts.
     */
    fun placeNameParts(
        locality: String?,
        subAdministrativeArea: String?,
        administrativeArea: String?,
        country: String?,
        name: String?,
    ): List<String> {
        val parts = listOfNotNull(locality ?: subAdministrativeArea, administrativeArea ?: country)
            .filter { it.isNotEmpty() }.distinct()
        return parts.ifEmpty { listOfNotNull(name?.takeIf { it.isNotEmpty() }) }
    }
}
