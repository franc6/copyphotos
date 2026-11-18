package app.copyphotos.ui

import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.copyphotos.R
import app.copyphotos.core.CopyRunState
import app.copyphotos.core.LibraryAccess
import app.copyphotos.core.ResultBanner
import app.copyphotos.media.MediaItem
import app.copyphotos.media.requiredPermissions
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/** The dialogs the Photos screen shows, one at a time. */
private enum class PhotosDialog { COPY_AGAIN, COPY_ALL, FORGET, DELETE, FAILURES }

/**
 * The Photos screen: the library with which photos are copied, copying, and the copy history.
 * @param vm the screen's controller.
 * @param details the photo viewer's Info panel controller.
 * @param onPermissionsChanged called after the user answered the permission request.
 * @param modifier the screen's modifier.
 */
@Composable
fun PhotosScreen(vm: PhotosViewModel, details: PhotoDetailsViewModel, onPermissionsChanged: () -> Unit, modifier: Modifier) {
    val access by vm.access.collectAsState()
    if (!access.canRead) {
        AccessNeeded(onPermissionsChanged, modifier)
        return
    }
    val library by vm.library.collectAsState()
    val runState by vm.runState.collectAsState()
    val canCopy by vm.canCopy.collectAsState()
    val banner by vm.banner.collectAsState()

    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var dialog by remember { mutableStateOf<PhotosDialog?>(null) }
    // The list the viewer pages through (as filtered when opened) and the photo it opened at.
    var viewer by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }

    val endSelecting = { selecting = false; selection = emptySet() }
    val copySelection = { vm.copyIds(selection); endSelecting() }

    Column(modifier.fillMaxSize()) {
        if (selecting) {
            SelectionTopBar(selection, library.visible, onDone = endSelecting, onSelect = { selection = it })
        } else {
            LibraryTopBar(
                canCopy, canChangeHistory = !runState.isActive, copiedOnDevice = library.copiedCount,
                onSelect = { selecting = true }, onDialog = { dialog = it },
            )
        }
        LibrarySummary(vm, library, access)
        PhotoGrid(library.visible, library.copiedIds, if (selecting) selection else null, Modifier.weight(1f),
            onToggle = { id -> selection = if (id in selection) selection - id else selection + id },
            onOpen = { item -> viewer = library.visible to library.visible.indexOf(item) })
        CopyStatus(runState, banner, onStop = vm::cancel, onShowFailures = { dialog = PhotosDialog.FAILURES },
            onDismiss = vm::dismissBanner)
        CopyButton(
            selection.takeIf { selecting }, library, canCopy,
            onCopySelection = { if (selection.any { it in library.copiedIds }) dialog = PhotosDialog.COPY_AGAIN else copySelection() },
            onCopyNew = vm::copyNew,
        )
    }

    dialog?.let { PhotosDialogView(it, vm, library, selection, banner, copySelection, onClose = { dialog = null }) }
    viewer?.let { (list, index) ->
        PhotoViewer(list, index, library.copiedIds, details, startWithDetails = false) { viewer = null }
    }
}

/**
 * Asks for access to the photo library.
 * @param onPermissionsChanged called after the user answered.
 * @param modifier the screen's modifier.
 */
@Composable
private fun AccessNeeded(onPermissionsChanged: () -> Unit, modifier: Modifier) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.photos_access_needed), style = MaterialTheme.typography.bodyLarge)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.photos_notifications_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) { Text(stringResource(R.string.action_allow_access)) }
    }
}

/**
 * The top bar while photos are being selected.
 * @param selection the selected photo IDs.
 * @param visible the photos shown.
 * @param onDone ends selecting.
 * @param onSelect replaces the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(selection: Set<Long>, visible: List<MediaItem>, onDone: () -> Unit, onSelect: (Set<Long>) -> Unit) {
    val all = selection.size == visible.size
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.photos_selected, selection.size, selection.size)) },
        navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, stringResource(R.string.action_done)) } },
        actions = {
            TextButton(onClick = { onSelect(if (all) emptySet() else visible.map { it.id }.toSet()) }) {
                Text(stringResource(if (all) R.string.select_none else R.string.select_all))
            }
        },
    )
}

/**
 * The top bar of the library, with Select and the menu.
 * @param canCopy a copy can start.
 * @param canChangeHistory no copy is running, so the history may change.
 * @param copiedOnDevice how many copied photos are still on the device.
 * @param onSelect starts selecting.
 * @param onDialog shows a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    canCopy: Boolean,
    canChangeHistory: Boolean,
    copiedOnDevice: Int,
    onSelect: () -> Unit,
    onDialog: (PhotosDialog) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            TextButton(onClick = onSelect) { Text(stringResource(R.string.action_select)) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_copy_all_again)) }, enabled = canCopy,
                    onClick = { menu = false; onDialog(PhotosDialog.COPY_ALL) })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete_copied)) },
                    enabled = canChangeHistory && copiedOnDevice > 0,
                    onClick = { menu = false; onDialog(PhotosDialog.DELETE) })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_forget_history)) }, enabled = canChangeHistory,
                    onClick = { menu = false; onDialog(PhotosDialog.FORGET) })
            }
        },
    )
}

/**
 * Where photos are copied to, the counts, and the filter.
 * @param vm the screen's controller.
 * @param library the library as shown.
 * @param access how much of the library the app may read.
 */
@Composable
private fun LibrarySummary(vm: PhotosViewModel, library: LibraryState, access: LibraryAccess) {
    val destinationUrl by vm.destinationUrl.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            destinationUrl ?: stringResource(R.string.no_destination),
            style = MaterialTheme.typography.labelMedium,
            color = if (destinationUrl != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(joined(listOf(
            pluralStringResource(R.plurals.photos_on_device, library.items.size, library.items.size),
            pluralStringResource(R.plurals.photos_copied, library.copiedCount, library.copiedCount),
            pluralStringResource(R.plurals.photos_not_copied, library.uncopiedCount, library.uncopiedCount),
        )),
            style = MaterialTheme.typography.bodyMedium)
        if (access == LibraryAccess.LIMITED) {
            Text(stringResource(R.string.photos_limited_access),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            PhotoFilter.entries.forEachIndexed { i, f ->
                SegmentedButton(selected = library.filter == f, onClick = { vm.setFilter(f) },
                    shape = SegmentedButtonDefaults.itemShape(i, PhotoFilter.entries.size)) { Text(f.label()) }
            }
        }
    }
}

/**
 * The photos as a grid of thumbnails.
 * @param visible the photos shown.
 * @param copied the IDs of the copied photos.
 * @param selection the selected IDs while selecting, else null.
 * @param modifier the grid's modifier.
 * @param onToggle selects or deselects a photo, while selecting.
 * @param onOpen opens a photo in the viewer, otherwise.
 */
@Composable
private fun PhotoGrid(
    visible: List<MediaItem>,
    copied: Set<Long>,
    selection: Set<Long>?,
    modifier: Modifier,
    onToggle: (Long) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(visible, key = { it.id }) { item ->
            Thumbnail(item, item.id in copied, selection?.let { item.id in it }) {
                if (selection != null) onToggle(item.id) else onOpen(item)
            }
        }
    }
}

/**
 * The running copy's progress, or else the last copy's banner.
 * @param runState whether a copy runs.
 * @param banner the last copy's banner, if not closed.
 * @param onStop stops the copy.
 * @param onShowFailures shows the files that failed.
 * @param onDismiss closes the banner.
 */
@Composable
private fun CopyStatus(
    runState: CopyRunState,
    banner: ResultBanner?,
    onStop: () -> Unit,
    onShowFailures: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (runState.isActive) {
        CopyProgressBar(runState, onStop = onStop)
    } else if (banner != null) {
        ResultBannerView(banner, onShowFailures = onShowFailures, onDismiss = onDismiss)
    }
}

/**
 * Copies the selection while selecting, or else the photos not copied yet.
 * @param selection the selected IDs while selecting, else null.
 * @param library the library as shown.
 * @param canCopy a copy can start.
 * @param onCopySelection copies the selection.
 * @param onCopyNew copies the photos not copied yet.
 */
@Composable
private fun CopyButton(
    selection: Set<Long>?,
    library: LibraryState,
    canCopy: Boolean,
    onCopySelection: () -> Unit,
    onCopyNew: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(16.dp)) {
        if (selection != null) {
            Button(onClick = onCopySelection, enabled = selection.isNotEmpty() && canCopy, modifier = Modifier.fillMaxWidth()) {
                Text(pluralStringResource(R.plurals.copy_selected, selection.size, selection.size))
            }
        } else {
            val uncopied = library.uncopiedCount
            Button(onClick = onCopyNew, enabled = canCopy && uncopied > 0, modifier = Modifier.fillMaxWidth()) {
                Text(pluralStringResource(R.plurals.copy_new, uncopied, uncopied))
            }
        }
    }
}

/**
 * One of the screen's dialogs.
 * @param dialog which dialog.
 * @param vm the screen's controller.
 * @param library the library as shown.
 * @param selection the selected IDs.
 * @param banner the last copy's banner, for its failures.
 * @param copySelection copies the selection.
 * @param onClose closes the dialog.
 */
@Composable
private fun PhotosDialogView(
    dialog: PhotosDialog,
    vm: PhotosViewModel,
    library: LibraryState,
    selection: Set<Long>,
    banner: ResultBanner?,
    copySelection: () -> Unit,
    onClose: () -> Unit,
) {
    when (dialog) {
        PhotosDialog.COPY_AGAIN -> {
            val again = selection.count { it in library.copiedIds }
            ConfirmDialog(
                stringResource(R.string.confirm_copy_again_title),
                pluralStringResource(R.plurals.confirm_copy_again_button, selection.size, selection.size),
                copySelection, onDismiss = onClose,
            ) {
                Text(pluralStringResource(R.plurals.confirm_copy_again_text, again, again))
            }
        }
        PhotosDialog.COPY_ALL -> ConfirmDialog(stringResource(R.string.confirm_copy_all_title),
            stringResource(R.string.action_copy_all), vm::copyAllAgain, onDismiss = onClose) {
            Text(pluralStringResource(R.plurals.confirm_copy_all_text, library.items.size, library.items.size))
        }
        PhotosDialog.FORGET -> ConfirmDialog(stringResource(R.string.confirm_forget_title),
            stringResource(R.string.action_forget), vm::forgetHistory, onDismiss = onClose) {
            Text(stringResource(R.string.confirm_forget_text))
        }
        PhotosDialog.DELETE -> DeleteCopiedDialog(vm, library.copiedCount, onClose)
        PhotosDialog.FAILURES -> if (banner != null) {
            ConfirmDialog(stringResource(R.string.errors_title), stringResource(R.string.action_ok), onConfirm = {},
                onDismiss = onClose, dismissLabel = null) {
                SelectionContainer { Text(banner.failures.map { it.text() }.joinToString("\n\n")) }
            }
        }
    }
}

/**
 * One photo's tile: its thumbnail, whether it is copied or a video, and its selection.
 * @param item the photo.
 * @param copied the photo is copied.
 * @param selected whether it is selected while selecting, else null.
 * @param onClick called when the tile is tapped.
 */
@Composable
private fun Thumbnail(item: MediaItem, copied: Boolean, selected: Boolean?, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)
    ) {
        var state by remember(item.id) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        // Software bitmap so its pixels can be sampled for cropLooksBlank.
        val context = LocalContext.current
        val request = remember(item.uri) { ImageRequest.Builder(context).data(item.uri).allowHardware(false).build() }
        // Crop to fill the tile, unless the cropped part would look blank: then show the whole image, scaled to fit.
        var fit by remember(item.id) { mutableStateOf(false) }
        AsyncImage(
            model = request, contentDescription = item.displayName,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            onState = {
                state = it
                if (it is AsyncImagePainter.State.Success) {
                    fit = (it.result.drawable as? BitmapDrawable)?.bitmap?.let(::cropLooksBlank) ?: false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (state !is AsyncImagePainter.State.Success) ThumbnailPlaceholder(state, item.isVideo)
        ThumbnailBadges(copied, item.isVideo, selected)
    }
}

/**
 * What the item is, until its thumbnail is drawn or when it can't be read, so a tile is never blank.
 * @param state the thumbnail's loading state.
 * @param isVideo the item is a video.
 */
@Composable
private fun BoxScope.ThumbnailPlaceholder(state: AsyncImagePainter.State, isVideo: Boolean) {
    Icon(
        when {
            state is AsyncImagePainter.State.Error -> Icons.Default.BrokenImage
            isVideo -> Icons.Default.Movie
            else -> Icons.Default.Image
        },
        null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.align(Alignment.Center).size(32.dp),
    )
}

/**
 * The marks on a tile: copied, video, and the selection circle.
 * @param copied the photo is copied.
 * @param isVideo the item is a video.
 * @param selected whether it is selected while selecting, else null.
 */
@Composable
private fun BoxScope.ThumbnailBadges(copied: Boolean, isVideo: Boolean, selected: Boolean?) {
    if (copied) {
        // White check on a green disc, with a white rim so it shows on any photo.
        Icon(Icons.Default.CheckCircle, stringResource(R.string.cd_copied), tint = CopiedGreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.White, CircleShape).size(20.dp))
    }
    if (isVideo) {
        Icon(Icons.Default.Videocam, stringResource(R.string.cd_video), tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp))
    }
    if (selected != null) {
        Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
    }
}

/** Deletes every copied photo from the device. The state and steps live in [PhotosViewModel]. */
@Composable
fun DeleteCopiedDialog(vm: PhotosViewModel, copiedCount: Int, onClose: () -> Unit) {
    val state by vm.delete.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onDeleteConfirmed(result.resultCode == Activity.RESULT_OK)
    }
    // Launch the next system confirmation when a batch is waiting.
    val next = state.pending.firstOrNull()
    LaunchedEffect(next) {
        next?.let { launcher.launch(IntentSenderRequest.Builder(it.first.intentSender).build()) }
    }
    val close = { vm.resetDelete(); onClose() }

    AlertDialog(
        onDismissRequest = { if (!state.working) close() },
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.delete_copied_count, copiedCount, copiedCount))
                Text(stringResource(R.string.delete_explanation),
                    style = MaterialTheme.typography.bodySmall)
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                state.done?.let { Text(it.text(), color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.working && state.done == null && copiedCount > 0, onClick = vm::startDelete) {
                Text(pluralStringResource(R.plurals.delete_button, copiedCount, copiedCount), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !state.working, onClick = close) { Text(stringResource(if (state.done != null) R.string.action_done else R.string.action_cancel)) } },
    )
}
