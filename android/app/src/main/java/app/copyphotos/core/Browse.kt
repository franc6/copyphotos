package app.copyphotos.core

/** A place in the location browser: a folder inside a share ("" is the share root). */
data class BrowseLocation(val share: String, val path: String) {
    val name: String get() = if (path.isEmpty()) share else RemotePath.name(path)

    fun child(name: String) = BrowseLocation(share, RemotePath.join(path, name))

    companion object {
        /**
         * The browser opens at the folder already chosen, with every parent folder on the back
         * stack. Empty when no share is chosen yet (the browser then shows the server's shares).
         */
        fun stack(config: DestinationConfig): List<BrowseLocation> {
            if (config.share.isEmpty()) return emptyList()
            val target = config.location
            var loc = BrowseLocation(target.share, "")
            val stack = mutableListOf(loc)
            target.path.split('/').filter { it.isNotEmpty() }.forEach { loc = loc.child(it); stack += loc }
            return stack
        }
    }
}

/** What the browser shows for one level. */
sealed class BrowseListing {
    data class Entries(val entries: List<RemoteEntry>) : BrowseListing()
    /** The folder does not exist (yet). */
    data object Missing : BrowseListing()
    data class Failed(val reason: FailureReason) : BrowseListing()
}

/** Shares as the browser lists them: hidden/admin (`$`) shares left out, sorted by name. */
fun browsableShares(shares: List<RemoteEntry>): List<RemoteEntry> =
    shares.filter { !it.name.endsWith("$") }.sortedBy { it.name.lowercase() }

/** A folder as the browser lists it: hidden (dot) entries left out, folders first, then by name. */
fun browsableEntries(entries: List<RemoteEntry>): List<RemoteEntry> =
    entries.filter { !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
