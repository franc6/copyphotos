package app.copyphotos.core

enum class SmbAuth { USER_PASSWORD, GUEST, ANONYMOUS }

/**
 * The configured SMB destination. The SMB password is stored separately, encrypted.
 * [host] and [share] are stored normalized; see [normalizeHost] and [normalizeShare].
 */
data class DestinationConfig(
    val host: String = "",
    /** Optional port override (default 445). */
    val port: Int? = null,
    /** SMB share name. */
    val share: String = "",
    /** Folder inside the share to copy into. */
    val path: String = "Photos",
    val smbAuth: SmbAuth = SmbAuth.USER_PASSWORD,
    val username: String = "",
    val domain: String = "",
    val verifyMode: VerifyMode = VerifyMode.READ_BACK_CHECKSUM,
    /** Automatically copy photos that have never been copied. */
    val autoCopy: Boolean = false,
    val autoCopyWifiOnly: Boolean = true,
    val autoCopyWhileCharging: Boolean = false,
) {
    /** Server and sign-in are filled in; enough to browse for a share. */
    val hasServerAndSignIn: Boolean
        get() = host.isNotEmpty() && (smbAuth != SmbAuth.USER_PASSWORD || username.isNotBlank())

    val isComplete: Boolean get() = hasServerAndSignIn && share.isNotEmpty()

    /**
     * Whether file transfers are encrypted (SMB 3). Always when signing in with a password. Guest
     * and anonymous sessions have no session key to derive encryption keys from, so they can't be.
     */
    val encrypted: Boolean get() = smbAuth == SmbAuth.USER_PASSWORD

    /** Copy new photos automatically: switched on, and there is somewhere to copy to. */
    val autoCopyEnabled: Boolean get() = autoCopy && isComplete

    /** "smb://host", "smb://host/share" or "smb://host/share/folder". */
    fun url(share: String?, path: String = ""): String {
        val rest = share?.let { RemotePath.join(it, path) }.orEmpty()
        return if (rest.isEmpty()) "smb://$host" else "smb://$host/$rest"
    }

    /** Identifies the destination in copy records, e.g. "smb://nas/Photos". */
    val destinationKey: String get() = url(share)

    val displayUrl: String get() = url(share, path)

    /** The chosen folder, with [path] normalized. */
    val location: BrowseLocation get() = BrowseLocation(share, RemotePath.join(path))

    val copyOptions: CopyOptions get() = CopyOptions(destinationKey, path, verifyMode)

    companion object {
        /** A host name as typed, without surrounding whitespace (same rules as iOS). */
        fun normalizeHost(input: String): String = input.trim()

        /** A share name as typed, without surrounding whitespace or slashes ("\\Photos/" → "Photos"). */
        fun normalizeShare(input: String): String = input.trim { it.isWhitespace() || it == '/' || it == '\\' }
    }
}
