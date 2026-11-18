package app.copyphotos.core

import java.io.IOException

/** Why copying an item failed. The views turn it into text. */
sealed class CopyError : IOException() {
    /** The file at the destination has a different size than the local one ([remote] null: missing). */
    data class SizeMismatch(val local: Long, val remote: Long?) : CopyError()
    data object ChecksumMismatch : CopyError()
    /** A file had the wrong size after it was moved into place. */
    data class WrongSizeAfterMove(val path: String) : CopyError()
    data class TooManyNameConflicts(val name: String) : CopyError()
    /** The photo is no longer on the device. */
    data object ItemMissing : CopyError()
}

/** Why a destination could not be used. The views turn it into text. */
sealed class DestinationError : IOException() {
    /** Server, sign-in or share is missing from the configuration. */
    data object Incomplete : DestinationError()
    data object InvalidHost : DestinationError()
    data class SignInRefused(val host: String, val share: String?) : DestinationError()
    data class ConnectionRefused(val host: String) : DestinationError()
    data class AccessDenied(val host: String, val share: String?) : DestinationError()
    data class ShareMissing(val host: String, val share: String) : DestinationError()
    data class NotADiskShare(val host: String, val share: String) : DestinationError()
    data class TimedOut(val host: String) : DestinationError()
    data class Unreachable(val host: String) : DestinationError()
    /** Anything else; [detail] is the system's own description, when it has one. */
    data class Other(val host: String, val code: Int, val detail: String?) : DestinationError()
}

/** Why something failed, as data. The views turn it into (translated) text. */
sealed class FailureReason {
    data class Copy(val error: CopyError) : FailureReason()
    data class Destination(val error: DestinationError) : FailureReason()
    /** No file or folder at the path. */
    data object NotFound : FailureReason()
    /** Any other error, with the system's own description of it. */
    data class Other(val message: String) : FailureReason()

    companion object {
        fun of(e: Throwable): FailureReason = when (e) {
            is CopyError -> Copy(e)
            is DestinationError -> Destination(e)
            is RemoteNotFoundException -> NotFound
            else -> Other(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** An item that could not be copied, and why. */
data class CopyFailure(val fileName: String, val reason: FailureReason)
