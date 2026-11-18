package app.copyphotos.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Where the destination's settings and password are saved. Implemented by the platform
 * (SharedPreferences and the Android Keystore); only [DestinationService] uses it.
 */
interface DestinationStore {
    /**
     * The saved settings.
     * @return the settings, or defaults if none are saved.
     */
    fun loadConfig(): DestinationConfig

    /**
     * Saves the settings.
     * @param config the settings to save.
     */
    fun saveConfig(config: DestinationConfig)

    /** Whether a password is saved. */
    val hasPassword: Boolean

    /**
     * Saves the password; an empty one removes it.
     * @param password the new password.
     * @throws Exception when the secure store refuses it.
     */
    fun setPassword(password: String)
}

/**
 * Opens connections to a destination. Implemented by the SMB adapter, which reads the password
 * from the secure store itself. All calls block.
 */
interface RemoteConnector {
    /**
     * Connects to the share root (not to `config.path`).
     * @param config the destination to connect to.
     * @return the share's file system.
     */
    fun open(config: DestinationConfig): RemoteFileSystem

    /**
     * The shares on a server, as the server lists them.
     * @param config the destination whose host and sign-in to use.
     * @return the shares.
     */
    fun listShares(config: DestinationConfig): List<RemoteEntry>
}

/**
 * The destination and everything done with it: its settings, saving its password, testing it,
 * browsing it for a folder, and connecting to it for a copy. Nothing else reads the settings
 * store. Calls that reach the server block.
 */
class DestinationService(private val store: DestinationStore, private val connector: RemoteConnector) {
    private val _config = MutableStateFlow(store.loadConfig())
    /** The saved settings, for the screen that edits them. Everything else asks the service. */
    val config: StateFlow<DestinationConfig> = _config.asStateFlow()

    private val _hasPassword = MutableStateFlow(store.hasPassword)
    val hasPassword: StateFlow<Boolean> = _hasPassword.asStateFlow()

    /** There is somewhere to copy to. */
    val isReady: Boolean get() = config.value.isComplete

    /** "smb://host/share/folder". */
    val displayUrl: String get() = config.value.displayUrl

    /** Changes the settings and saves them. Returns false if nothing changed. */
    @Synchronized
    fun update(transform: (DestinationConfig) -> DestinationConfig): Boolean {
        val new = transform(_config.value)
        if (new == _config.value) return false
        _config.value = new
        store.saveConfig(new)
        return true
    }

    /**
     * Saves the password; an empty one removes it.
     * @param password the new password.
     * @return false if the secure store refused it.
     */
    fun setPassword(password: String): Boolean =
        try {
            store.setPassword(password)
            true
        } catch (_: Exception) {
            false
        } finally {
            _hasPassword.value = store.hasPassword
        }

    // region Copying

    /** Where and how a copy writes. */
    val copyOptions: CopyOptions get() = config.value.copyOptions

    /** Connects to the configured share. Throws [DestinationError.Incomplete] if it isn't set up. */
    fun connect(): RemoteFileSystem = open(config.value)

    /** Connects and proves the destination folder is writable (see [DestinationProbe]). */
    fun test(): ProbeResult {
        val c = config.value
        return runCatching { open(c).use { DestinationProbe.check(it, c.path) } }
            .fold({ ProbeResult.Succeeded }, { ProbeResult.Failed(FailureReason.of(it)) })
    }

    private fun open(c: DestinationConfig): RemoteFileSystem {
        if (!c.isComplete) throw DestinationError.Incomplete
        return connector.open(c)
    }

    // endregion

    // region Location browser

    private val connections = mutableMapOf<String, RemoteFileSystem>()

    /** Where the browser opens: the folder already chosen, with its parents on the back stack. */
    fun browseStack(): List<BrowseLocation> = BrowseLocation.stack(config.value)

    /** The server's shares ([location] null) or the contents of a folder. */
    fun listing(location: BrowseLocation?): BrowseListing = try {
        if (location == null) {
            val c = config.value
            if (c.host.isEmpty()) throw DestinationError.InvalidHost
            BrowseListing.Entries(browsableShares(connector.listShares(c)))
        } else {
            BrowseListing.Entries(browsableEntries(fileSystem(location.share).list(location.path)))
        }
    } catch (_: RemoteNotFoundException) {
        BrowseListing.Missing
    } catch (e: Exception) {
        location?.let { dropConnection(it.share) }
        BrowseListing.Failed(FailureReason.of(e))
    }

    /** Creates a folder in [location] and returns it. */
    fun createFolder(name: String, location: BrowseLocation): BrowseLocation {
        val child = location.child(RemotePath.sanitizeFileName(name))
        fileSystem(location.share).ensureDirectory(child.path)
        return child
    }

    /** Sets the share and the folder together, and ends browsing. Returns false if nothing changed. */
    fun choose(location: BrowseLocation): Boolean =
        try {
            update { it.copy(share = location.share, path = location.path) }
        } finally {
            endBrowsing()
        }

    /** "smb://host/share/folder" for a place in the browser (the server itself for null). */
    fun displayUrl(location: BrowseLocation?): String = config.value.url(location?.share, location?.path.orEmpty())

    /** Closes the browser's connections (in the background, so it never blocks the caller). */
    fun endBrowsing() {
        val all = synchronized(connections) { connections.values.toList().also { connections.clear() } }
        close(all)
    }

    private fun fileSystem(share: String): RemoteFileSystem = synchronized(connections) {
        connections.getOrPut(share) { open(config.value.copy(share = share)) }
    }

    private fun dropConnection(share: String) {
        close(listOfNotNull(synchronized(connections) { connections.remove(share) }))
    }

    private fun close(all: List<RemoteFileSystem>) {
        if (all.isNotEmpty()) thread(name = "close-connections") { all.forEach { runCatching { it.close() } } }
    }

    // endregion
}

/** The outcome of testing a destination with [DestinationProbe]. */
sealed class ProbeResult {
    data object Succeeded : ProbeResult()
    data class Failed(val reason: FailureReason) : ProbeResult()
}

/**
 * Proves a destination is writable: creates the folder, then writes, reads back and deletes a
 * small probe file. The probe lives in the staging folder, so nothing appears in the folder itself.
 */
object DestinationProbe {
    fun check(fs: RemoteFileSystem, basePath: String) {
        val base = RemotePath.join(basePath)
        fs.ensureDirectory(base)
        val staging = RemotePath.join(base, CopyEngine.STAGING_FOLDER)
        fs.ensureDirectory(staging)
        val probe = RemotePath.join(staging, ".copyphotos-probe-${UUID.randomUUID().toString().take(8)}")
        val payload = "CopyPhotos write test ${System.currentTimeMillis()}".toByteArray()
        fs.upload(ByteArrayInputStream(payload), probe) {}
        val digest = try {
            fs.readBackDigest(probe)
        } finally {
            runCatching { fs.removeFile(probe) }
        }
        if (digest.size != payload.size.toLong()) throw CopyError.SizeMismatch(payload.size.toLong(), digest.size)
    }
}
