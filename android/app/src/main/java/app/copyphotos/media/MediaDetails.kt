package app.copyphotos.media

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.copyphotos.core.CameraMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume

/** Everything the Details panel shows. Fields are null when the file doesn't carry them. */
data class MediaDetails(
    val fileName: String,
    val mimeType: String?,
    val format: String?,
    val size: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val takenAtMillis: Long?,
    /** Seconds from GMT where the photo was taken (EXIF), when known. */
    val takenOffsetSeconds: Int? = null,
    /** Folder on the device, e.g. "/storage/emulated/0/DCIM/Camera". */
    val folder: String?,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val fNumber: Double? = null,
    val exposureTime: Double? = null,
    val focalLength: Double? = null,
    val focalLength35: Int? = null,
    val iso: Int? = null,
    val exposureBias: Double? = null,
    val flashFired: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val durationMillis: Long? = null,
    val frameRate: Double? = null,
    val codec: String? = null,
    /** False when Android hid the location because ACCESS_MEDIA_LOCATION isn't granted. */
    val locationReadable: Boolean = true,
) {
    /** "Google Pixel 8". */
    val camera: String? get() = CameraMetadata.cameraName(make, model)
}

/** Reads [MediaDetails] for one item. Blocking; call off the main thread. */
class MediaDetailsReader(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    fun read(item: MediaItem): MediaDetails {
        val base = queryStore(item)
        return runCatching { if (item.isVideo) withVideo(item.uri, base) else withExif(item.uri, base) }.getOrDefault(base)
            .copy(locationReadable = app.hasMediaLocationAccess())
    }

    @Suppress("DEPRECATION") // DATA is still the only way to show the full folder path.
    private fun queryStore(item: MediaItem): MediaDetails {
        val cols = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(if (item.isVideo) MediaStore.Video.VideoColumns.DURATION else MediaStore.Images.ImageColumns.ORIENTATION)
        }.toTypedArray()
        var d = MediaDetails(
            fileName = item.displayName, mimeType = null, format = null, size = item.size, width = 0, height = 0,
            isVideo = item.isVideo, takenAtMillis = item.takenAtMillis, folder = null,
        )
        runCatching {
            resolver.query(item.uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
                val name = str(MediaStore.MediaColumns.DISPLAY_NAME) ?: item.displayName
                val mime = str(MediaStore.MediaColumns.MIME_TYPE)
                var w = long(MediaStore.MediaColumns.WIDTH)?.toInt() ?: 0
                var h = long(MediaStore.MediaColumns.HEIGHT)?.toInt() ?: 0
                // Photos stored rotated: show the size as the photo is seen.
                if (!item.isVideo && (long(MediaStore.Images.ImageColumns.ORIENTATION) ?: 0L) % 180 == 90L) { val t = w; w = h; h = t }
                val folder = str(MediaStore.MediaColumns.DATA)?.let { File(it).parent }
                    ?: str("relative_path")?.trimEnd('/')?.let { "/$it" }
                d = d.copy(
                    fileName = name, mimeType = mime, format = CameraMetadata.formatName(mime, name),
                    size = long(MediaStore.MediaColumns.SIZE) ?: item.size, width = w, height = h,
                    folder = folder,
                    durationMillis = if (item.isVideo) long(MediaStore.Video.VideoColumns.DURATION) else null,
                )
            }
        }
        return d
    }

    private fun withExif(uri: Uri, d: MediaDetails): MediaDetails {
        val exif = resolver.openInputStream(app.originalUri(uri))?.use { ExifInterface(it) } ?: return d
        fun dbl(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() }
        fun int(tag: String) = exif.getAttributeInt(tag, -1).takeIf { it >= 0 }
        val latLong = exif.latLong?.takeIf { it[0] != 0.0 || it[1] != 0.0 }
        val w = int(ExifInterface.TAG_IMAGE_WIDTH) ?: int(ExifInterface.TAG_PIXEL_X_DIMENSION) ?: 0
        val h = int(ExifInterface.TAG_IMAGE_LENGTH) ?: int(ExifInterface.TAG_PIXEL_Y_DIMENSION) ?: 0
        val rotated = exif.rotationDegrees % 180 == 90
        val offset = CameraMetadata.timeZoneOffset(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        // MediaStore often has no DATE_TAKEN (e.g. files copied onto the device), and the item then
        // carries the date it was added; the camera's own capture time is the right one.
        val taken = CameraMetadata.parseExifDateTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), offset, ZoneId.systemDefault())
        return d.copy(
            takenAtMillis = taken ?: d.takenAtMillis,
            width = d.width.takeIf { it > 0 } ?: if (rotated) h else w,
            height = d.height.takeIf { it > 0 } ?: if (rotated) w else h,
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            fNumber = dbl(ExifInterface.TAG_F_NUMBER),
            exposureTime = dbl(ExifInterface.TAG_EXPOSURE_TIME),
            focalLength = dbl(ExifInterface.TAG_FOCAL_LENGTH),
            focalLength35 = int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 },
            iso = int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it > 0 },
            exposureBias = dbl(ExifInterface.TAG_EXPOSURE_BIAS_VALUE),
            flashFired = int(ExifInterface.TAG_FLASH)?.let { it and 1 == 1 },
            takenOffsetSeconds = offset,
            latitude = latLong?.get(0), longitude = latLong?.get(1),
            altitude = exif.getAltitude(Double.NaN).takeIf { it.isFinite() && latLong != null },
        )
    }

    private fun withVideo(uri: Uri, d: MediaDetails): MediaDetails {
        var out = d
        val src = app.originalUri(uri)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, src)
            fun key(k: Int) = r.extractMetadata(k)
            val rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation % 180 == 90) { val t = w; w = h; h = t }
            val loc = CameraMetadata.parseIso6709(key(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            out = out.copy(
                width = w.takeIf { it > 0 } ?: out.width,
                height = h.takeIf { it > 0 } ?: out.height,
                durationMillis = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: out.durationMillis,
                frameRate = key(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it > 0 },
                latitude = loc?.first, longitude = loc?.second,
            )
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        // Codec and frame rate from the video track.
        val ex = MediaExtractor()
        try {
            ex.setDataSource(app, src, null)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                        .recoverCatching { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()
                } else null
                out = out.copy(codec = CameraMetadata.codecName(mime), frameRate = out.frameRate ?: fps?.takeIf { it > 0 })
                break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { ex.release() }
        }
        return out
    }

    /** The parts of the place's name ("Paris", "France"); empty when offline or with no geocoder. */
    suspend fun placeName(lat: Double, lon: Double): List<String> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(app, Locale.getDefault())
        val address: Address? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()) }
                        override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
        }.getOrNull()
        address ?: return emptyList()
        return CameraMetadata.placeNameParts(
            locality = address.locality, subAdministrativeArea = address.subAdminArea,
            administrativeArea = address.adminArea, country = address.countryName, name = address.featureName,
        )
    }
}
