package app.copyphotos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.copyphotos.R
import app.copyphotos.core.BrowseListing
import app.copyphotos.core.BrowseLocation
import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.FailureReason
import app.copyphotos.core.RemoteEntry
import kotlinx.coroutines.launch

/**
 * One browser for the whole destination, like a folder picker on a computer: the server's
 * shares, then the folders inside them. "Choose" sets the share and the folder together.
 * It opens at the folder that is already selected; Back goes up one level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationBrowserDialog(vm: DestinationViewModel, onClose: () -> Unit) {
    val config by vm.config.collectAsState()
    // Back stack of folders; empty means the server level (list of shares).
    var stack by remember { mutableStateOf(vm.browseStack()) }
    // Pull-to-refresh keeps the current rows on screen while the new listing loads.
    var refreshing by remember { mutableStateOf(false) }
    // null while loading.
    var listing by remember { mutableStateOf<BrowseListing?>(null) }
    var createError by remember { mutableStateOf<FailureReason?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var openByName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val current = stack.lastOrNull()

    LaunchedEffect(current) {
        listing = null
        createError = null
        listing = vm.listing(current)
    }
    val status = ListingStatus(
        loading = listing == null,
        missing = listing == BrowseListing.Missing,
        error = ((listing as? BrowseListing.Failed)?.reason ?: createError)?.text(),
    )
    val entries = (listing as? BrowseListing.Entries)?.entries.orEmpty()

    val close = { vm.closeBrowser(); onClose() }
    val up = { stack = stack.dropLast(1) }
    BackHandler { if (stack.isEmpty()) close() else up() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                BrowserTopBar(current?.name ?: config.host, atServer = current == null, canCreate = !status.missing,
                    onClose = close, onUp = up, onNewFolder = { newFolder = "" })
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            listing = vm.listing(current)
                            refreshing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        statusRows(status)
                        if (current == null) {
                            shareRows(entries, status, onOpen = { stack = listOf(BrowseLocation(it, "")) },
                                onOpenByName = { openByName = "" })
                        } else {
                            folderRows(entries, status, onOpen = { stack = stack + current.child(it) })
                        }
                    }
                }
                HorizontalDivider()
                ChooseBar(vm.displayUrl(current), current, onChoose = { vm.choose(it); onClose() })
            }
        }
    }

    newFolder?.let { name ->
        NewFolderDialog(name, current, onValueChange = { newFolder = it }, onDismiss = { newFolder = null }) { here, n ->
            scope.launch {
                vm.createFolder(n, here)
                    .onSuccess { stack = stack + it } // open the new folder
                    .onFailure { createError = FailureReason.of(it) }
            }
        }
    }
    openByName?.let { name ->
        OpenShareDialog(name, onValueChange = { openByName = it }, onDismiss = { openByName = null }) {
            stack = listOf(BrowseLocation(it, ""))
        }
    }
}

/**
 * What the current listing says besides its entries.
 * @param loading the listing is loading.
 * @param missing the folder doesn't exist yet.
 * @param error why the listing or creating a folder failed, as text.
 */
private data class ListingStatus(val loading: Boolean, val missing: Boolean, val error: String?) {
    /** Loaded, with nothing to explain. */
    val settled: Boolean get() = !loading && !missing && error == null
}

/**
 * The browser's top bar: where it is, back or cancel, and New folder.
 * @param title the current share or folder, or the server.
 * @param atServer the browser shows the server's shares.
 * @param canCreate a folder can be created here.
 * @param onClose closes the browser.
 * @param onUp goes up one level.
 * @param onNewFolder asks for a new folder's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    title: String,
    atServer: Boolean,
    canCreate: Boolean,
    onClose: () -> Unit,
    onUp: () -> Unit,
    onNewFolder: () -> Unit,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (atServer) IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
            else IconButton(onClick = onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_up)) }
        },
        actions = {
            if (!atServer) {
                IconButton(onClick = onNewFolder, enabled = canCreate) {
                    Icon(Icons.Default.CreateNewFolder, stringResource(R.string.browser_new_folder))
                }
            }
        },
    )
}

/**
 * Loading, error and missing-folder rows.
 * @param status what the listing says.
 */
private fun LazyListScope.statusRows(status: ListingStatus) {
    if (status.loading) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
    status.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
    if (status.missing) item {
        Text(stringResource(R.string.browser_missing),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

/**
 * The server's shares, and Open by name.
 * @param shares the shares.
 * @param status what the listing says.
 * @param onOpen opens a share by name.
 * @param onOpenByName asks for a share's name.
 */
private fun LazyListScope.shareRows(
    shares: List<RemoteEntry>,
    status: ListingStatus,
    onOpen: (String) -> Unit,
    onOpenByName: () -> Unit,
) {
    items(shares) { s ->
        ListItem(headlineContent = { Text(s.name) }, leadingContent = { Icon(Icons.Default.Storage, null) },
            modifier = Modifier.clickable { onOpen(s.name) })
    }
    if (!status.loading && status.error == null && shares.isEmpty()) item {
        Text(stringResource(R.string.browser_no_shares), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item {
        TextButton(onClick = onOpenByName, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.browser_open_by_name))
        }
    }
}

/**
 * A folder's subfolders, which open, and files, which are only shown.
 * @param entries the folder's entries.
 * @param status what the listing says.
 * @param onOpen opens a subfolder by name.
 */
private fun LazyListScope.folderRows(entries: List<RemoteEntry>, status: ListingStatus, onOpen: (String) -> Unit) {
    items(entries) { e ->
        if (e.isDirectory) {
            ListItem(headlineContent = { Text(e.name) },
                leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onOpen(e.name) })
        } else {
            ListItem(headlineContent = { Text(e.name, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
    if (status.settled && entries.isEmpty()) item {
        Text(stringResource(R.string.browser_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Where the browser is, and Choose.
 * @param displayUrl the current place's URL.
 * @param current the current share or folder, or null at the server.
 * @param onChoose chooses it.
 */
@Composable
private fun ChooseBar(displayUrl: String, current: BrowseLocation?, onChoose: (BrowseLocation) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(displayUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Button(onClick = { current?.let(onChoose) }, enabled = current != null, modifier = Modifier.fillMaxWidth()) {
            Text(current?.let { stringResource(R.string.browser_choose, it.name) } ?: stringResource(R.string.browser_select_share))
        }
    }
}

/**
 * Asks for a new folder's name.
 * @param name the name typed so far.
 * @param current where the folder is created.
 * @param onValueChange the name was edited.
 * @param onDismiss closes the dialog.
 * @param onCreate creates the folder in a place, with a trimmed, non-empty name.
 */
@Composable
private fun NewFolderDialog(
    name: String,
    current: BrowseLocation?,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (BrowseLocation, String) -> Unit,
) {
    TextInputDialog(
        title = stringResource(R.string.browser_new_folder_title, current?.name.orEmpty()), label = stringResource(R.string.field_name),
        value = name, onValueChange = onValueChange,
        confirmLabel = stringResource(R.string.action_create),
        onConfirm = { typed ->
            val n = typed.trim()
            if (current != null && n.isNotEmpty()) onCreate(current, n)
        },
        onDismiss = onDismiss,
    )
}

/**
 * Asks for a share's name, for servers that don't list their shares.
 * @param name the name typed so far.
 * @param onValueChange the name was edited.
 * @param onDismiss closes the dialog.
 * @param onOpen opens the share, by its normalized, non-empty name.
 */
@Composable
private fun OpenShareDialog(name: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    TextInputDialog(
        title = stringResource(R.string.browser_open_share_title), label = stringResource(R.string.field_share),
        value = name, onValueChange = onValueChange,
        confirmLabel = stringResource(R.string.action_open),
        onConfirm = { typed ->
            val n = DestinationConfig.normalizeShare(typed)
            if (n.isNotEmpty()) onOpen(n)
        },
        onDismiss = onDismiss,
    )
}
