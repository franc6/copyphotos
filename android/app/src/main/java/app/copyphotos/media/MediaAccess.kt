package app.copyphotos.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.copyphotos.core.LibraryAccess

/**
 * How much of the library the app can read. Android can't tell "not asked yet" from "denied",
 * so both are [LibraryAccess.NONE]; the app then offers to ask.
 */
fun libraryAccess(context: Context): LibraryAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> LibraryAccess.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LibraryAccess.LIMITED
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> LibraryAccess.FULL
        else -> LibraryAccess.NONE
    }
}

/** Everything the app asks for at once: media read (+location metadata), notifications, legacy delete. */
fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

/** Whether location metadata in photos is readable (ACCESS_MEDIA_LOCATION on Android 10+). */
internal fun Context.hasMediaLocationAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The unredacted file when allowed, else [uri] itself. Without this, Android removes the
 * location from the EXIF data it hands out.
 */
internal fun Context.originalUri(uri: Uri): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocationAccess()) MediaStore.setRequireOriginal(uri) else uri

/** The content URI of a photo or video in MediaStore. */
internal fun mediaUri(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
)
