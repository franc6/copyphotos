package app.copyphotos.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.copyphotos.R
import app.copyphotos.core.CopyRecord
import app.copyphotos.media.MediaDetails
import app.copyphotos.media.MediaItem
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Full-screen viewer, like Google Photos: swipe between photos, tap to hide the bars,
 * swipe down (or Back) to close, and ⓘ or swipe up for Details.
 */
@Composable
fun PhotoViewer(
    items: List<MediaItem>,
    startIndex: Int,
    /** Copy history, so Details reads the copy record again when it changes. */
    copiedIds: Set<Long>,
    /** The Details panel's controller. */
    details: PhotoDetailsViewModel,
    startWithDetails: Boolean,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
        var chrome by rememberSaveable { mutableStateOf(true) }
        var showDetails by rememberSaveable { mutableStateOf(startWithDetails) }
        val current = items.getOrNull(pager.currentPage)

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -120f) showDetails = true else if (total > 200f) onClose() },
                    ) { _, dy -> total += dy }
                },
        ) {
            HorizontalPager(pager, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { chrome = !chrome }) {
                    AsyncImage(
                        model = item.uri, contentDescription = item.displayName,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) {
                        Icon(Icons.Outlined.Videocam, stringResource(R.string.cd_video), tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(48.dp)
                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(24.dp)).padding(10.dp))
                    }
                }
            }

            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        current?.takenAtMillis?.let { t ->
                            val (date, time) = dateAndTime(t, null, showZone = false)
                            Text(date, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(time, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { showDetails = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.action_details), tint = Color.White) }
                }
            }
        }

        if (showDetails && current != null) {
            DetailsSheet(details, current, isCopied = current.id in copiedIds) { showDetails = false }
        }
    }
}

/** The Details panel, laid out like Google Photos': one row per kind of information. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(vm: PhotoDetailsViewModel, item: MediaItem, isCopied: Boolean, onDismiss: () -> Unit) {
    // Until this item is loaded, the ViewModel may still hold the previous one.
    val current = vm.itemId.collectAsState().value == item.id
    val details = vm.details.collectAsState().value.takeIf { current }
    val place = placeName(vm.place.collectAsState().value).takeIf { current }
    val record = vm.record.collectAsState().value.takeIf { current }
    LaunchedEffect(item.id) { vm.load(item) }
    LaunchedEffect(isCopied) { vm.reloadRecord() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.details_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val d = details
            if (d == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
                return@Column
            }

            d.takenAtMillis?.let { t ->
                val (date, time) = dateAndTime(t, d.takenOffsetSeconds)
                DetailRow(Icons.Outlined.Event, date, time)
            }

            DetailRow(
                if (d.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
                d.fileName,
                listOfNotNull(sizeLine(d), videoLine(d)).joinToString("\n"),
                selectable = true,
                trailing = d.format,
            )

            CameraRow(d)
            LocationRow(d, place)

            d.folder?.let { DetailRow(Icons.Outlined.Folder, stringResource(R.string.details_on_device), it, selectable = true) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            CopyRow(record, d)
        }
    }
}

/**
 * The camera, exposure and lens, when the file records any of them.
 * @param d the photo's details.
 */
@Composable
private fun CameraRow(d: MediaDetails) {
    val exposure = joined(listOfNotNull(
        d.fNumber?.let { MetadataText.fNumber(it) },
        d.exposureTime?.let { MetadataText.exposureTime(it) },
        d.focalLength?.let { MetadataText.focalLength(it) },
        d.iso?.let { MetadataText.iso(it) },
        d.exposureBias?.takeIf { it != 0.0 }?.let { MetadataText.exposureBias(it) },
        if (d.flashFired == true) stringResource(R.string.details_flash) else null,
    ))
    val camera = d.camera
    if (camera == null && exposure.isEmpty()) return
    DetailRow(
        Icons.Outlined.PhotoCamera,
        camera ?: stringResource(R.string.details_camera),
        listOfNotNull(
            exposure.ifEmpty { null },
            d.lens,
            d.focalLength35?.let { stringResource(R.string.details_focal_35, it) },
        ).joinToString("\n"),
    )
}

/**
 * Where the photo was taken, which opens a map; or why no place is shown.
 * @param d the photo's details.
 * @param place the place's name, when known.
 */
@Composable
private fun LocationRow(d: MediaDetails, place: String?) {
    val context = LocalContext.current
    if (d.latitude == null || d.longitude == null) {
        DetailRow(
            Icons.Outlined.LocationOff, stringResource(R.string.details_no_location),
            if (!d.locationReadable) stringResource(R.string.details_location_permission) else null,
        )
        return
    }
    val coords = joined(listOfNotNull(
        MetadataText.coordinate(d.latitude, d.longitude),
        d.altitude?.let { stringResource(R.string.details_altitude, Math.round(it).toInt()) },
    ))
    val placeTitle = place ?: stringResource(R.string.details_location)
    DetailRow(Icons.Outlined.Place, placeTitle, coords) {
        val label = Uri.encode(placeTitle)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${d.latitude},${d.longitude}?q=${d.latitude},${d.longitude}($label)"))
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }
}

/**
 * Whether and where CopyPhotos copied the photo.
 * @param record the copy record, or null if not copied.
 * @param d the photo's details.
 */
@Composable
private fun CopyRow(record: CopyRecord?, d: MediaDetails) {
    if (record != null) {
        DetailRow(
            Icons.Outlined.CheckCircle, stringResource(R.string.details_copied),
            joined(listOf(dateTimeShort(record.copiedAt), formatBytes(record.files.sumOf { it.size }))),
            code = record.files.joinToString("\n") { "${record.destination}/${it.remotePath}" },
            iconTint = CopiedGreen,
        )
    } else {
        DetailRow(Icons.Outlined.RadioButtonUnchecked, stringResource(R.string.details_not_copied),
            joined(listOf(d.fileName, formatBytes(d.size))))
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    selectable: Boolean = false,
    trailing: String? = null,
    /** Shown below the subtitle in monospace and selectable, e.g. a path on the share. */
    code: String? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = {
            if (selectable) SelectionContainer { Text(title) } else Text(title)
        },
        supportingContent = if (subtitle.isNullOrEmpty() && code == null) null else {
            {
                Column {
                    if (!subtitle.isNullOrEmpty()) {
                        if (selectable) SelectionContainer { Text(subtitle) } else Text(subtitle)
                    }
                    if (code != null) SelectionContainer { Text(code, fontFamily = FontFamily.Monospace) }
                }
            }
        },
        trailingContent = trailing?.let { t ->
            {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                    Text(t, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        },
    )
}

/** "12 MP · 4032 × 3024 · 2.1 MB", or "1080p · 1920 × 1080 · 30 fps · 12 MB" for a video. */
@Composable
private fun sizeLine(d: MediaDetails): String = joined(listOfNotNull(
    if (d.isVideo) MetadataText.videoResolution(d.width, d.height) else MetadataText.megapixels(d.width, d.height),
    if (d.width > 0 && d.height > 0) stringResource(R.string.details_dimensions, d.width, d.height) else null,
    d.frameRate?.let { MetadataText.frameRate(it) },
    if (d.size > 0) formatBytes(d.size) else null,
))

@Composable
private fun videoLine(d: MediaDetails): String? {
    if (!d.isVideo) return null
    return joined(listOfNotNull(d.codec, d.durationMillis?.let { MetadataText.duration(it) })).ifEmpty { null }
}

/** ("Tue, 14 Jul 2026", "18:42 · GMT+02:00"), in the zone the photo was taken in when known. */
@Composable
private fun dateAndTime(millis: Long, offsetSeconds: Int?, showZone: Boolean = true): Pair<String, String> {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val zone: ZoneId = offsetSeconds?.let { ZoneOffset.ofTotalSeconds(it) } ?: ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy"), locale).format(t)
    val timeSkeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val time = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, timeSkeleton), locale).format(t)
    val offset = t.offset.totalSeconds
    val gmt = if (offset == 0) "GMT" else "GMT" + DateTimeFormatter.ofPattern("xxx").format(t)
    return date to if (showZone) joined(listOf(time, gmt)) else time
}

private fun dateTimeShort(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
