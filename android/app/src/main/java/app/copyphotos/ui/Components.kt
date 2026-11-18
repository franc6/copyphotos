package app.copyphotos.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.copyphotos.R
import app.copyphotos.core.CopyRunState
import app.copyphotos.core.ResultBanner

/** The "copied" check mark and label (same green as iOS). */
val CopiedGreen = Color(0xFF2E7D32)

/**
 * A dialog with a confirm button and, unless [dismissLabel] is null, a dismiss button.
 * Both buttons close it; [onConfirm] runs after it is closed.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = stringResource(R.string.action_cancel),
    text: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = dismissLabel?.let { { TextButton(onClick = onDismiss) { Text(it) } } },
    )
}

/** A dialog asking for one line of text, e.g. a folder name. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(title, confirmLabel, onConfirm = { onConfirm(value) }, onDismiss = onDismiss) {
        OutlinedTextField(value, onValueChange, singleLine = true, label = { Text(label) })
    }
}

/** "2.1 MB", as the system shows file sizes in this language. */
@Composable
fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The running copy: which item, how far, and Stop (same as iOS). */
@Composable
fun CopyProgressBar(runState: CopyRunState, onStop: () -> Unit) {
    val p = runState.progress
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        p != null -> stringResource(R.string.run_copying, minOf(p.completed + p.failed + 1, p.total), p.total)
                        runState is CopyRunState.Waiting -> stringResource(R.string.run_waiting)
                        else -> stringResource(R.string.run_starting)
                    },
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
            if (p != null && p.total > 0) {
                LinearProgressIndicator(progress = { (p.completed + p.failed).toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                p.currentName?.let {
                    Text(joined(listOf(it, formatBytes(p.currentBytes))), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (p.failed > 0) {
                    Text(pluralStringResource(R.plurals.run_failed, p.failed, p.failed),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** The result of the last copy, with Show errors and Dismiss (same as iOS). */
@Composable
fun ResultBannerView(banner: ResultBanner, onShowFailures: () -> Unit, onDismiss: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(if (banner.isError) Icons.Default.Warning else Icons.Default.CheckCircle, null,
                tint = if (banner.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(banner.title(), style = MaterialTheme.typography.titleSmall)
                Text(banner.detail(), style = MaterialTheme.typography.bodySmall)
                if (banner.failures.isNotEmpty()) {
                    TextButton(onClick = onShowFailures) { Text(stringResource(R.string.banner_show_errors)) }
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_dismiss)) }
        }
    }
}
