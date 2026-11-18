package app.copyphotos.remote

import app.copyphotos.core.Digest
import app.copyphotos.core.RemoteEntry
import app.copyphotos.core.RemoteFileSystem
import app.copyphotos.core.RemoteNotFoundException
import app.copyphotos.core.RemotePath
import app.copyphotos.core.forEachChunk
import app.copyphotos.core.sha256Of
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.EnumSet

/** A connected SMB 2/3 share, via smbj. [SmbConnector] signs in and opens it. */
class SmbFileSystem internal constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : RemoteFileSystem {

    private companion object {
        /** True for "no such file or folder" errors. */
        fun isNotFound(e: Throwable) = e is SMBApiException && e.status in setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_NOT_FOUND,
        )
    }

    private fun smb(path: String) = RemotePath.join(path).replace('/', '\\')

    /** Opens the file at [path], shared with everyone else, for [access]. */
    private fun open(
        path: String,
        access: Set<AccessMask>,
        disposition: SMB2CreateDisposition,
        options: Set<SMB2CreateOptions>?,
        attributes: Set<FileAttributes>? = null,
    ) = share.openFile(smb(path), access, attributes, SMB2ShareAccess.ALL, disposition, options)

    override fun ensureDirectory(path: String) {
        var cur = ""
        for (c in path.split('/').filter { it.isNotEmpty() }) {
            cur = RemotePath.join(cur, c)
            if (!share.folderExists(smb(cur))) {
                try {
                    share.mkdir(smb(cur))
                } catch (e: SMBApiException) {
                    if (!share.folderExists(smb(cur))) throw e
                }
            }
        }
    }

    override fun sizeOfItem(path: String): Long? = try {
        share.getFileInformation(smb(path)).standardInformation.endOfFile
    } catch (e: SMBApiException) {
        if (isNotFound(e)) null else throw e
    }

    override fun upload(input: InputStream, path: String, progress: (Long) -> Unit) {
        // FILE_CREATE is exclusive: fails if the name exists, so nothing existing is touched.
        val file = open(
            path, EnumSet.of(AccessMask.GENERIC_WRITE), SMB2CreateDisposition.FILE_CREATE,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
        )
        try {
            file.use { f ->
                f.outputStream.use { out ->
                    var total = 0L
                    forEachChunk(input) { buf, n ->
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                    out.flush()
                }
                f.flush()
            }
        } catch (e: Exception) {
            runCatching { removeFile(path) } // the file this upload created
            throw e
        }
    }

    override fun readBackDigest(path: String): Digest =
        open(path, EnumSet.of(AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            .use { file -> sha256Of(file.inputStream) }

    override fun move(from: String, to: String) {
        open(from, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), SMB2CreateDisposition.FILE_OPEN, null)
            .use { it.rename(smb(to), false) } // ReplaceIfExists = false
    }

    override fun removeFile(path: String) {
        try {
            share.rm(smb(path))
        } catch (e: SMBApiException) {
            if (!isNotFound(e)) throw e
        }
    }

    override fun list(path: String): List<RemoteEntry> =
        try {
            share.list(smb(path))
        } catch (e: SMBApiException) {
            throw if (isNotFound(e)) RemoteNotFoundException(path) else e
        }
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteEntry(it.fileName, isDir, it.endOfFile)
            }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
