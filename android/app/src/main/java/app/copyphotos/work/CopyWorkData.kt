package app.copyphotos.work

import android.content.Context
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.copyphotos.core.CopyError
import app.copyphotos.core.CopyFailure
import app.copyphotos.core.CopyProgress
import app.copyphotos.core.CopyResult
import app.copyphotos.core.CopyRunState
import app.copyphotos.core.DestinationError
import app.copyphotos.core.FailureReason
import app.copyphotos.core.ResultBanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/** The copy as the app sees it: whether it runs, and the banner of the run that finished last. */
data class CopyJobState(
    val runState: CopyRunState,
    /** ID of the finished work, so a banner the user closed stays closed. */
    val finishedWorkId: String? = null,
    val banner: ResultBanner? = null,
)

/**
 * The only place that knows how a copy's input, progress and result are stored in WorkManager
 * [Data], including how [FailureReason]s are written as text fields. Everyone else uses
 * [CopyProgress], [CopyResult] and [CopyJobState].
 */
object CopyWorkData {
    private const val KEY_MODE = "mode"
    private const val KEY_IDS_FILE = "idsFile"
    private const val MODE_NEW = "new"
    private const val MODE_IDS = "ids"
    private const val KEY_DONE = "done"
    private const val KEY_FAILED = "failed"
    private const val KEY_TOTAL = "total"
    private const val KEY_NAME = "name"
    private const val KEY_BYTES = "bytes"
    private const val KEY_COPIED = "copied"
    private const val KEY_PRESENT = "present"
    private const val KEY_ERRORS = "errors"
    private const val KEY_ERROR = "error"
    private const val KEY_CANCELLED = "cancelled"

    // region Input

    fun copyNew(): Data = workDataOf(KEY_MODE to MODE_NEW)

    private const val IDS_FILE_PREFIX = "copy-ids-"

    /** IDs go in a file: WorkManager Data is limited to 10 KB. */
    fun copyIds(context: Context, ids: Collection<Long>): Data {
        val file = File(context.cacheDir, "$IDS_FILE_PREFIX${System.nanoTime()}.txt")
        file.writeText(ids.joinToString("\n"))
        return workDataOf(KEY_MODE to MODE_IDS, KEY_IDS_FILE to file.absolutePath)
    }

    /**
     * The IDs to copy, or null for "copy new photos". Deletes every IDs file: only one copy is
     * ever queued (unique work, KEEP), so any other file belongs to a request that was dropped.
     */
    fun idsToCopy(context: Context, input: Data): List<Long>? {
        val file = input.getString(KEY_IDS_FILE)?.let(::File)
        val ids = if (input.getString(KEY_MODE) != MODE_IDS) null
        else file?.takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        context.cacheDir.listFiles { f -> f.name.startsWith(IDS_FILE_PREFIX) }?.forEach { it.delete() }
        return ids
    }

    // endregion

    // region Output

    fun progress(p: CopyProgress): Data = workDataOf(
        KEY_DONE to p.completed, KEY_FAILED to p.failed, KEY_TOTAL to p.total,
        KEY_NAME to p.currentName, KEY_BYTES to p.currentBytes,
    )

    fun result(r: CopyResult): Data = workDataOf(
        // WorkManager Data is limited to 10 KB; keep only the first failures, shortened.
        KEY_COPIED to r.copied, KEY_PRESENT to r.alreadyPresent, KEY_FAILED to r.failed,
        KEY_ERRORS to r.failures.take(25).joinToString("\n") { encode(listOf(it.fileName) + encode(it.reason)) },
        KEY_CANCELLED to r.cancelled,
    )

    /** The copy could not run at all (e.g. could not connect). */
    fun error(reason: FailureReason): Data = workDataOf(KEY_ERROR to encode(encode(reason)))

    // endregion

    /**
     * The state of the copy, as WorkManager reports it.
     * @param workManager runs the copy.
     * @return the state, updated as it changes.
     */
    fun observe(workManager: WorkManager): Flow<CopyJobState> =
        workManager.getWorkInfosForUniqueWorkFlow(CopyScheduler.UNIQUE).map { infos ->
            val info = infos.firstOrNull() ?: return@map CopyJobState(CopyRunState.Idle)
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> CopyJobState(CopyRunState.Waiting)
                WorkInfo.State.RUNNING -> CopyJobState(CopyRunState.Running(readProgress(info.progress)))
                else -> CopyJobState(CopyRunState.Idle, info.id.toString(), banner(info))
            }
        }

    private fun readProgress(p: Data): CopyProgress? =
        if (p.keyValueMap.isEmpty()) null
        else CopyProgress(p.getInt(KEY_DONE, 0), p.getInt(KEY_FAILED, 0), p.getInt(KEY_TOTAL, 0), p.getString(KEY_NAME), p.getLong(KEY_BYTES, 0))

    private fun banner(info: WorkInfo): ResultBanner? {
        val out = info.outputData
        out.getString(KEY_ERROR)?.let { return ResultBanner.ConnectFailed(decodeReason(decode(it))) }
        if (info.state == WorkInfo.State.CANCELLED) return ResultBanner.Stopped
        if (info.state != WorkInfo.State.SUCCEEDED) return null
        return ResultBanner.from(
            CopyResult(
                copied = out.getInt(KEY_COPIED, 0),
                alreadyPresent = out.getInt(KEY_PRESENT, 0),
                failed = out.getInt(KEY_FAILED, 0),
                failures = out.getString(KEY_ERRORS).orEmpty().lines().filter { it.isNotBlank() }.map { line ->
                    val fields = decode(line)
                    CopyFailure(fields.first(), decodeReason(fields.drop(1)))
                },
                cancelled = out.getBoolean(KEY_CANCELLED, false),
            )
        )
    }

    // region Failure reasons as text fields (tab-separated, one record per line)

    private const val SEP = '\t'

    private fun encode(fields: List<String>): String =
        fields.joinToString(SEP.toString()) { it.replace(SEP, ' ').replace('\n', ' ').take(200) }

    private fun decode(record: String): List<String> = record.split(SEP)

    /**
     * A failure reason as text fields: a tag, then the error's values.
     * @param r the reason.
     * @return the fields.
     */
    private fun encode(r: FailureReason): List<String> = when (r) {
        is FailureReason.Copy -> encode(r.error)
        is FailureReason.Destination -> encode(r.error)
        FailureReason.NotFound -> listOf("notFound")
        is FailureReason.Other -> listOf("other", r.message)
    }

    /**
     * A copy error as text fields.
     * @param e the error.
     * @return the fields.
     */
    private fun encode(e: CopyError): List<String> = when (e) {
        is CopyError.SizeMismatch -> listOf("size", e.local.toString(), e.remote?.toString().orEmpty())
        CopyError.ChecksumMismatch -> listOf("checksum")
        is CopyError.WrongSizeAfterMove -> listOf("moved", e.path)
        is CopyError.TooManyNameConflicts -> listOf("names", e.name)
        CopyError.ItemMissing -> listOf("missing")
    }

    /**
     * A destination error as text fields.
     * @param e the error.
     * @return the fields.
     */
    private fun encode(e: DestinationError): List<String> = when (e) {
        DestinationError.Incomplete -> listOf("incomplete")
        DestinationError.InvalidHost -> listOf("invalidHost")
        is DestinationError.SignInRefused -> listOf("signIn", e.host, e.share.orEmpty())
        is DestinationError.ConnectionRefused -> listOf("refused", e.host)
        is DestinationError.AccessDenied -> listOf("denied", e.host, e.share.orEmpty())
        is DestinationError.ShareMissing -> listOf("noShare", e.host, e.share)
        is DestinationError.NotADiskShare -> listOf("notDisk", e.host, e.share)
        is DestinationError.TimedOut -> listOf("timeout", e.host)
        is DestinationError.Unreachable -> listOf("unreachable", e.host)
        is DestinationError.Other -> listOf("destOther", e.host, e.code.toString(), e.detail.orEmpty())
    }

    /**
     * A failure reason from the text fields [encode] made.
     * @param f the fields.
     * @return the reason; an unknown tag becomes [FailureReason.Other].
     */
    private fun decodeReason(f: List<String>): FailureReason {
        val at = { i: Int -> f.getOrElse(i) { "" } }
        decodeCopyError(at)?.let { return FailureReason.Copy(it) }
        decodeDestinationError(at)?.let { return FailureReason.Destination(it) }
        return if (at(0) == "notFound") FailureReason.NotFound else FailureReason.Other(at(1))
    }

    /**
     * A copy error from text fields.
     * @param at the field at an index ("" past the end).
     * @return the error, or null if the tag isn't a copy error's.
     */
    private fun decodeCopyError(at: (Int) -> String): CopyError? = when (at(0)) {
        "size" -> CopyError.SizeMismatch(at(1).toLongOrNull() ?: 0, at(2).toLongOrNull())
        "checksum" -> CopyError.ChecksumMismatch
        "moved" -> CopyError.WrongSizeAfterMove(at(1))
        "names" -> CopyError.TooManyNameConflicts(at(1))
        "missing" -> CopyError.ItemMissing
        else -> null
    }

    /**
     * A destination error from text fields.
     * @param at the field at an index ("" past the end).
     * @return the error, or null if the tag isn't a destination error's.
     */
    private fun decodeDestinationError(at: (Int) -> String): DestinationError? = when (at(0)) {
        "incomplete" -> DestinationError.Incomplete
        "invalidHost" -> DestinationError.InvalidHost
        "signIn" -> DestinationError.SignInRefused(at(1), at(2).ifEmpty { null })
        "refused" -> DestinationError.ConnectionRefused(at(1))
        "denied" -> DestinationError.AccessDenied(at(1), at(2).ifEmpty { null })
        "noShare" -> DestinationError.ShareMissing(at(1), at(2))
        "notDisk" -> DestinationError.NotADiskShare(at(1), at(2))
        "timeout" -> DestinationError.TimedOut(at(1))
        "unreachable" -> DestinationError.Unreachable(at(1))
        "destOther" -> DestinationError.Other(at(1), at(2).toIntOrNull() ?: 0, at(3).ifEmpty { null })
        else -> null
    }

    // endregion
}
