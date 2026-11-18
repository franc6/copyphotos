package app.copyphotos.media

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import app.copyphotos.core.CopyError
import app.copyphotos.core.CopyItem
import app.copyphotos.core.CopySource
import app.copyphotos.core.MediaLibrary
import java.io.FileNotFoundException
import java.io.InputStream

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val takenAtMillis: Long?,
    val size: Long,
    val isVideo: Boolean,
) {
    fun toCopyItem() = CopyItem(id, displayName, takenAtMillis, isVideo, size)
}

/** What deleting items from the device needs. */
sealed class DeleteRequest {
    /**
     * Android 11+: system dialogs the user must confirm, one per batch, each with the IDs it
     * deletes. Items go to the trash (recoverable for ~30 days).
     */
    data class Confirm(val batches: List<Pair<PendingIntent, List<Long>>>) : DeleteRequest()
    /** Android 8–10: already deleted directly (requires storage permission). */
    data class Done(val deletedIds: List<Long>) : DeleteRequest()
}

/** Reads photos/videos from MediaStore and deletes them on request. */
class MediaRepository(context: Context) : MediaLibrary, CopySource {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    private val filesUri: Uri = MediaStore.Files.getContentUri("external")

    /** All photos and videos, newest first. */
    fun query(): List<MediaItem> = query(PHOTOS_AND_VIDEOS)

    /** These photos and videos, if they are still on the device. */
    fun byIds(ids: Collection<Long>): List<MediaItem> =
        // IDs are numbers, so listing them inline is safe and avoids SQLite's limit on bound arguments.
        // Batches keep each statement well under SQLite's maximum length.
        ids.chunked(5000).flatMap { batch -> query("$PHOTOS_AND_VIDEOS AND ${FileColumns._ID} IN (${batch.joinToString(",")})") }

    override fun copyItems(ids: Collection<Long>): List<CopyItem> = byIds(ids).map { it.toCopyItem() }

    /** Every photo and video on the device, ID to size. Reads only those two columns. */
    override fun allSizes(): Map<Long, Long> =
        resolver.query(filesUri, arrayOf(FileColumns._ID, FileColumns.SIZE), PHOTOS_AND_VIDEOS, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
        }.orEmpty()

    private fun query(selection: String): List<MediaItem> {
        val projection = arrayOf(
            FileColumns._ID,
            FileColumns.DISPLAY_NAME,
            FileColumns.MEDIA_TYPE,
            FileColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            FileColumns.DATE_ADDED,
        )
        val out = mutableListOf<MediaItem>()
        resolver.query(filesUri, projection, selection, null, "${FileColumns.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(projection[0])
            val nameCol = c.getColumnIndexOrThrow(projection[1])
            val typeCol = c.getColumnIndexOrThrow(projection[2])
            val sizeCol = c.getColumnIndexOrThrow(projection[3])
            val takenCol = c.getColumnIndexOrThrow(projection[4])
            val addedCol = c.getColumnIndexOrThrow(projection[5])
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == FileColumns.MEDIA_TYPE_VIDEO
                val taken = if (c.isNull(takenCol)) null else c.getLong(takenCol).takeIf { it > 0 }
                val added = if (c.isNull(addedCol)) null else c.getLong(addedCol) * 1000
                out += MediaItem(
                    id = id,
                    uri = mediaUri(id, isVideo),
                    displayName = c.getString(nameCol) ?: "$id.${if (isVideo) "mp4" else "jpg"}",
                    takenAtMillis = taken ?: added,
                    size = c.getLong(sizeCol),
                    isVideo = isVideo,
                )
            }
        }
        return out
    }

    /**
     * Opens the original bytes. With ACCESS_MEDIA_LOCATION granted, location EXIF is
     * preserved (otherwise Android redacts it from the stream).
     */
    override fun open(item: CopyItem): InputStream {
        val uri = app.originalUri(mediaUri(item.mediaId, item.isVideo))
        val stream = try {
            resolver.openInputStream(uri)
        } catch (_: FileNotFoundException) {
            null
        }
        return stream ?: throw CopyError.ItemMissing
    }

    /** Deletes these items from the device, or asks the system to (see [DeleteRequest]). */
    fun deleteRequest(ids: Collection<Long>): DeleteRequest {
        val items = byIds(ids)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            DeleteRequest.Confirm(items.chunked(500).map { batch ->
                MediaStore.createTrashRequest(resolver, batch.map { it.uri }, true) to batch.map { it.id }
            })
        } else {
            DeleteRequest.Done(items.filter {
                runCatching { resolver.delete(it.uri, null, null) > 0 }.getOrDefault(false)
            }.map { it.id })
        }
    }

    private companion object {
        val PHOTOS_AND_VIDEOS =
            "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE},${FileColumns.MEDIA_TYPE_VIDEO})"
    }
}
