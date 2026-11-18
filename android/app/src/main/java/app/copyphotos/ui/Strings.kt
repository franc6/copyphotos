package app.copyphotos.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.copyphotos.R
import app.copyphotos.core.CopyError
import app.copyphotos.core.CopyFailure
import app.copyphotos.core.DestinationError
import app.copyphotos.core.FailureReason
import app.copyphotos.core.ResultBanner
import app.copyphotos.core.SmbAuth
import app.copyphotos.core.VerifyMode

// The one place that turns typed results, errors and enums into text, from strings.xml.

@Composable
fun ResultBanner.title(): String = stringResource(
    when (this) {
        is ResultBanner.Finished -> when {
            result.cancelled -> R.string.banner_stopped
            result.failed > 0 -> R.string.banner_with_errors
            else -> R.string.banner_complete
        }
        ResultBanner.NothingNew -> R.string.banner_nothing_new
        ResultBanner.Stopped -> R.string.banner_stopped
        is ResultBanner.ConnectFailed -> R.string.banner_connect_failed
    }
)

@Composable
fun ResultBanner.detail(): String = when (this) {
    is ResultBanner.Finished -> joined(
        listOfNotNull(
            pluralStringResource(R.plurals.banner_copied, result.copied, result.copied),
            if (result.alreadyPresent > 0) {
                pluralStringResource(R.plurals.banner_already_present, result.alreadyPresent, result.alreadyPresent)
            } else null,
            if (result.failed > 0) pluralStringResource(R.plurals.banner_failed, result.failed, result.failed) else null,
        )
    )
    ResultBanner.NothingNew -> stringResource(R.string.banner_nothing_new_detail)
    ResultBanner.Stopped -> stringResource(R.string.banner_stopped_detail)
    is ResultBanner.ConnectFailed -> reason.text()
}

@Composable
fun CopyFailure.text(): String = stringResource(R.string.failure_line, fileName, reason.text())

/** The failure reason as a sentence. */
@Composable
fun FailureReason.text(): String = when (this) {
    is FailureReason.Copy -> error.text()
    is FailureReason.Destination -> error.text()
    FailureReason.NotFound -> stringResource(R.string.error_not_found)
    is FailureReason.Other -> message
}

/** The copy error as a sentence. */
@Composable
fun CopyError.text(): String = when (this) {
    is CopyError.SizeMismatch -> remote?.let { stringResource(R.string.error_size_mismatch, local, it) }
        ?: stringResource(R.string.error_size_missing)
    CopyError.ChecksumMismatch -> stringResource(R.string.error_checksum)
    is CopyError.WrongSizeAfterMove -> stringResource(R.string.error_wrong_size_after_move, path)
    is CopyError.TooManyNameConflicts -> stringResource(R.string.error_name_conflicts, name)
    CopyError.ItemMissing -> stringResource(R.string.error_item_missing)
}

/** The destination error as a sentence. */
@Composable
fun DestinationError.text(): String = when (this) {
    DestinationError.Incomplete -> stringResource(R.string.error_incomplete)
    DestinationError.InvalidHost -> stringResource(R.string.error_invalid_host)
    is DestinationError.SignInRefused -> share?.let { stringResource(R.string.error_sign_in_share, host, it) }
        ?: stringResource(R.string.error_sign_in, host)
    is DestinationError.ConnectionRefused -> stringResource(R.string.error_connection_refused, host)
    is DestinationError.AccessDenied -> share?.let { stringResource(R.string.error_access_denied_share, host, it) }
        ?: stringResource(R.string.error_access_denied, host)
    is DestinationError.ShareMissing -> stringResource(R.string.error_share_missing, host, share)
    is DestinationError.NotADiskShare -> stringResource(R.string.error_not_disk_share, host, share)
    is DestinationError.TimedOut -> stringResource(R.string.error_timed_out, host)
    is DestinationError.Unreachable -> stringResource(R.string.error_unreachable, host)
    // The server's own text, when it gave one, says more than the code.
    is DestinationError.Other -> detail ?: stringResource(R.string.error_other, host, code)
}

@Composable
fun DeleteOutcome.text(): String = when (this) {
    is DeleteOutcome.Deleted -> pluralStringResource(R.plurals.delete_deleted, count, count)
    is DeleteOutcome.Trashed -> pluralStringResource(R.plurals.delete_trashed, count, count)
}

@Composable
fun SmbAuth.label(): String = stringResource(
    when (this) {
        SmbAuth.USER_PASSWORD -> R.string.smb_auth_user_password
        SmbAuth.GUEST -> R.string.smb_auth_guest
        SmbAuth.ANONYMOUS -> R.string.smb_auth_anonymous
    }
)

@Composable
fun VerifyMode.label(): String = stringResource(
    when (this) {
        VerifyMode.READ_BACK_CHECKSUM -> R.string.verify_read_back
        VerifyMode.SIZE_ONLY -> R.string.verify_size_only
    }
)

@Composable
fun PhotoFilter.label(): String = stringResource(
    when (this) {
        PhotoFilter.ALL -> R.string.filter_all
        PhotoFilter.NOT_COPIED -> R.string.filter_not_copied
        PhotoFilter.COPIED -> R.string.filter_copied
    }
)

/** A place name from its parts ("Paris, France"), or null when there are none. */
@Composable
fun placeName(parts: List<String>): String? =
    if (parts.isEmpty()) null else parts.joinToString(stringResource(R.string.place_separator))

/** Parts of one line, joined the way this language separates them ("a · b"). */
@Composable
fun joined(parts: List<String>): String = parts.joinToString(stringResource(R.string.list_separator))
