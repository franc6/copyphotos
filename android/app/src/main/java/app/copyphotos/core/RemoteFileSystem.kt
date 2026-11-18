package app.copyphotos.core

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long)

class Digest(val sha256: ByteArray, val size: Long) {
    val hex: String get() = sha256.joinToString("") { "%02x".format(it) }
}

/** Nothing exists at the path. */
class RemoteNotFoundException(path: String) : IOException(path)

/**
 * A connected share. Paths are relative to the share/export root, use "/" and have no
 * leading slash ("" is the root). Methods block; call them from a background thread.
 */
interface RemoteFileSystem : AutoCloseable {
    /** Creates the directory and any missing parents. */
    fun ensureDirectory(path: String)

    /** Size of the file at [path], or null if nothing exists there. */
    fun sizeOfItem(path: String): Long?

    /**
     * Writes directly to [path], which must not exist yet (exclusive create, so an existing file
     * is never touched). If writing fails after the file was created, removes the file it created.
     * The caller owns [input] and closes it.
     */
    fun upload(input: InputStream, path: String, progress: (Long) -> Unit)

    /** Reads the file back fully and returns its SHA-256 and size. */
    fun readBackDigest(path: String): Digest

    /** Moves a file within the share. Never replaces: fails if [to] already exists. */
    fun move(from: String, to: String)

    fun removeFile(path: String)

    /** Contents of a folder. Throws [RemoteNotFoundException] if the folder does not exist. */
    fun list(path: String): List<RemoteEntry>

    override fun close()
}

object RemotePath {
    /**
     * Joins path components into one share-relative path that can't climb above the share.
     * @param parts path components, each of which may itself contain `/` or `\`.
     * @return the path, `/`-separated, without empty, `.` or `..` segments.
     */
    fun join(vararg parts: String): String = join(parts.toList())

    /**
     * Joins path components into one share-relative path that can't climb above the share.
     * @param parts path components, each of which may itself contain `/` or `\`.
     * @return the path, `/`-separated, without empty, `.` or `..` segments.
     */
    fun join(parts: List<String>): String =
        parts.flatMap { it.split('/', '\\') }.filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    fun parent(path: String): String = path.split('/').filter { it.isNotEmpty() }.dropLast(1).joinToString("/")

    fun name(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: ""

    /** Makes a file name safe for SMB (Windows rules). */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c -> if (c in "\\/:*?\"<>|" || c.code < 32) '_' else c }
            .joinToString("")
            .trim(' ', '.')
        return cleaned.ifEmpty { "photo" }
    }

    /** "IMG_1.JPG", 2 -> "IMG_1_2.JPG" */
    fun numbered(name: String, n: Int): String {
        if (n <= 0) return name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "${name}_$n" else "${name.substring(0, dot)}_$n${name.substring(dot)}"
    }
}

/**
 * Reads [input] to the end in 1 MiB chunks, passing each to [chunk] as (buffer, length),
 * then closes it. Stops with [InterruptedIOException] when the thread is interrupted.
 */
inline fun forEachChunk(input: InputStream, chunk: (ByteArray, Int) -> Unit) {
    val buf = ByteArray(1 shl 20)
    input.use {
        while (true) {
            checkInterrupted()
            val n = it.read(buf)
            if (n < 0) break
            chunk(buf, n)
        }
    }
}

/** Streams [input] through SHA-256, then closes it. */
fun sha256Of(input: InputStream): Digest {
    val md = MessageDigest.getInstance("SHA-256")
    var total = 0L
    forEachChunk(input) { buf, n ->
        md.update(buf, 0, n)
        total += n
    }
    return Digest(md.digest(), total)
}

/** Lets blocking loops honour coroutine cancellation via runInterruptible. */
fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
}
