package app.copyphotos.core

import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

enum class VerifyMode {
    /** Read the uploaded file back and compare SHA-256 (strongest). */
    READ_BACK_CHECKSUM,
    /** Compare the size reported by the server with the local size (faster). */
    SIZE_ONLY,
}

/** Where and how to copy. */
data class CopyOptions(
    /** Identifies the destination in copy records, e.g. "smb://nas/Photos". */
    val destinationKey: String,
    /** Folder inside the share that files are copied into. */
    val basePath: String,
    val verifyMode: VerifyMode = VerifyMode.READ_BACK_CHECKSUM,
)

data class CopyItem(
    val mediaId: Long,
    val fileName: String,
    val takenAtMillis: Long?,
    val isVideo: Boolean,
    /** Size MediaStore reports for the item (0 if unknown); identifies the item in the ledger, see [CopyRecord.sourceSize]. */
    val size: Long = 0,
)

interface CopySource {
    /** Opens the original bytes of the photo. May be called more than once. */
    fun open(item: CopyItem): InputStream
}

data class CopyOutcome(
    val record: CopyRecord,
    /** An identical file was already at the destination, so nothing was uploaded. */
    val alreadyPresent: Boolean,
)

data class CopySummary(
    val copied: List<CopyRecord>,
    val alreadyPresent: List<CopyRecord>,
    val failures: List<CopyFailure>,
    val cancelled: Boolean,
)

data class CopyProgress(val completed: Int, val failed: Int, val total: Int, val currentName: String?, val currentBytes: Long)

/**
 * Copies photos one at a time with verification:
 *  1. hash the local original,
 *  2. if an identical file (same size, then same SHA-256) is already there under its name or a
 *     numbered variant, count that as the copy; otherwise pick the first free name,
 *  3. upload into the `.upload` folder and verify it there (read back + SHA-256, or size),
 *  4. move it into place under the chosen name (never replacing anything),
 *  5. record the copy in the ledger.
 * A photo is recorded only after all of this succeeds. All calls block.
 *
 * Unlike iOS, an item is a single file (MediaStore has no Live Photo or edit sidecars), so one
 * [Step] is planned per item instead of one per file.
 */
class CopyEngine(
    private val fs: RemoteFileSystem,
    private val source: CopySource,
    private val ledger: Ledger,
    private val options: CopyOptions,
) {
    fun copy(items: List<CopyItem>, isCancelled: () -> Boolean, progress: (CopyProgress) -> Unit): CopySummary {
        val copied = mutableListOf<CopyRecord>()
        val present = mutableListOf<CopyRecord>()
        val failures = mutableListOf<CopyFailure>()
        var cancelled = false
        var p = CopyProgress(0, 0, items.size, null, 0)
        progress(p)
        for (item in items) {
            if (isCancelled()) { cancelled = true; break }
            p = p.copy(currentName = item.fileName, currentBytes = 0)
            progress(p)
            try {
                val base = p
                val outcome = copyOne(item) { bytes -> progress(base.copy(currentBytes = bytes)) }
                if (outcome.alreadyPresent) present += outcome.record else copied += outcome.record
                p = p.copy(completed = p.completed + 1)
            } catch (e: Exception) {
                if (e is CancellationException || e is InterruptedIOException || isCancelled()) { cancelled = true; break }
                failures += CopyFailure(item.fileName, FailureReason.of(e))
                p = p.copy(failed = p.failed + 1)
            }
            progress(p)
        }
        progress(p.copy(currentName = null))
        return CopySummary(copied, present, failures, cancelled)
    }

    fun copyOne(item: CopyItem, progress: (Long) -> Unit = {}): CopyOutcome {
        val local: Digest = sha256Of(source.open(item))
        // Everything goes straight into the destination folder; no subfolders.
        val dir = RemotePath.join(options.basePath)
        fs.ensureDirectory(dir)
        val baseName = RemotePath.sanitizeFileName(item.fileName)

        val finalTarget = when (val step = planName(baseName, local, dir)) {
            is Step.Identical -> {
                val record = record(item, step.target, local)
                ledger.add(record)
                return CopyOutcome(record, alreadyPresent = true)
            }
            is Step.Upload -> step.target
        }

        // Upload and verify in the staging folder; only a verified file is moved into place.
        // The unique prefix means leftovers from an interrupted run never block a later one.
        val staging = RemotePath.join(options.basePath, STAGING_FOLDER)
        fs.ensureDirectory(staging)
        val staged = RemotePath.join(staging, "${UUID.randomUUID().toString().take(8)}-${RemotePath.name(finalTarget)}")
        try {
            source.open(item).use { fs.upload(it, staged, progress) }
            verify(staged, local)
            fs.move(staged, finalTarget) // never replaces
        } catch (e: Exception) {
            // Only the file this upload created in .upload is removed.
            runCatching { fs.removeFile(staged) }
            throw e
        }
        if (fs.sizeOfItem(finalTarget) != local.size) throw CopyError.WrongSizeAfterMove(finalTarget)

        val record = record(item, finalTarget, local)
        ledger.add(record)
        return CopyOutcome(record, alreadyPresent = false)
    }

    companion object {
        /** Folder inside the destination path where files are uploaded and verified before being moved into place. */
        const val STAGING_FOLDER = ".upload"
    }

    /** What to do with the item's file (iOS plans one per file of an item). */
    private sealed class Step {
        data class Upload(val target: String) : Step()
        data class Identical(val target: String) : Step()
    }

    /**
     * Never duplicates an identical file and never touches an existing one. Candidates are
     * `name`, `name_1`, `name_2`, … (matched case-insensitively, as SMB does):
     *  1. if any candidate is identical to the local file, it is the copy;
     *  2. otherwise the first free candidate is used.
     */
    private fun planName(baseName: String, local: Digest, dir: String): Step {
        // Names from the listing; sizes from sizeOfItem, because some servers omit sizes in listings.
        val existing = fs.list(dir).groupBy { it.name.lowercase() }
        val maxN = 1000
        for (n in 0 until maxN) {
            val entries = existing[RemotePath.numbered(baseName, n).lowercase()] ?: continue
            for (e in entries) {
                if (e.isDirectory) continue
                val path = RemotePath.join(dir, e.name)
                if (isIdentical(path, local)) return Step.Identical(path)
            }
        }
        for (n in 0 until maxN) {
            val name = RemotePath.numbered(baseName, n)
            if (name.lowercase() !in existing) return Step.Upload(RemotePath.join(dir, name))
        }
        throw CopyError.TooManyNameConflicts(baseName)
    }

    /**
     * Identical means same size AND same SHA-256. Size is checked first: a file of a
     * different size is different and is never read.
     */
    private fun isIdentical(path: String, local: Digest): Boolean {
        if (fs.sizeOfItem(path) != local.size) return false
        val remote = fs.readBackDigest(path)
        return remote.size == local.size && remote.sha256.contentEquals(local.sha256)
    }

    private fun record(item: CopyItem, path: String, d: Digest) =
        CopyRecord(item.mediaId, options.destinationKey, listOf(CopiedFile(path, d.size, d.hex)), System.currentTimeMillis(), item.size)

    private fun verify(path: String, local: Digest) {
        when (options.verifyMode) {
            VerifyMode.READ_BACK_CHECKSUM -> {
                val remote = fs.readBackDigest(path)
                if (remote.size != local.size) throw CopyError.SizeMismatch(local.size, remote.size)
                if (!remote.sha256.contentEquals(local.sha256)) throw CopyError.ChecksumMismatch
            }
            VerifyMode.SIZE_ONLY -> {
                val size = fs.sizeOfItem(path)
                if (size != local.size) throw CopyError.SizeMismatch(local.size, size)
            }
        }
    }
}
