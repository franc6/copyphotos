import Foundation

public enum SMBAuthMethod: String, Codable, CaseIterable, Identifiable, Sendable {
    /// Username + password (+ optional domain), negotiated as NTLMv2.
    case userPassword
    /// Guest / anonymous access.
    case guest
    public var id: String { rawValue }
}

/// The configured SMB destination. The SMB password is stored separately, not here.
/// `host` and `share` are stored normalized; see `normalizedHost` and `normalizedShare`.
public struct DestinationConfig: Codable, Equatable, Sendable {
    public var host: String = ""
    /// Optional port override (default 445).
    public var port: Int?
    /// Share name.
    public var share: String = ""
    /// Folder inside the share to copy into.
    public var path: String = "Photos"

    public var smbAuth: SMBAuthMethod = .userPassword
    public var username: String = ""
    public var domain: String = ""

    public var verifyMode: VerifyMode = .readBackChecksum

    /// Automatically copy photos that have not been copied yet (on launch and in background).
    public var autoCopy: Bool = false
    public var autoCopyRequiresPower: Bool = false

    public init() {}

    /// Server and sign-in are filled in; enough to browse for a share.
    public var hasServerAndSignIn: Bool {
        !host.isEmpty && (smbAuth == .guest || !username.isEmpty)
    }

    public var isComplete: Bool { hasServerAndSignIn && !share.isEmpty }

    /// Whether file transfers are encrypted (SMB 3). Always when signing in with a password. A
    /// guest session has no session key to derive encryption keys from, so it can't be encrypted.
    public var encrypted: Bool { smbAuth == .userPassword }

    /// Copy new photos automatically: switched on, and there is somewhere to copy to.
    public var autoCopyEnabled: Bool { autoCopy && isComplete }

    /// "smb://host", "smb://host/share" or "smb://host/share/folder".
    public func url(share: String?, path: String = "") -> String {
        let rest = share.map { RemotePath.join($0, path) } ?? ""
        return rest.isEmpty ? "smb://\(host)" : "smb://\(host)/\(rest)"
    }

    /// Identifies the destination in copy records, e.g. "smb://nas/Photos".
    public var destinationKey: String { url(share: share) }

    public var displayURL: String { url(share: share, path: path) }

    /// The chosen folder, with `path` normalized.
    public var location: BrowseLocation { BrowseLocation(share: share, path: RemotePath.join(path)) }

    public var copyOptions: CopyOptions {
        CopyOptions(destinationKey: destinationKey, basePath: path, verifyMode: verifyMode)
    }

    // MARK: Normalizing user input (same rules as Android)

    /// A host name as typed, without surrounding whitespace.
    public static func normalizedHost(_ input: String) -> String {
        input.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A share name as typed, without surrounding whitespace or slashes ("\\Photos/" → "Photos").
    public static func normalizedShare(_ input: String) -> String {
        input.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "/\\")))
    }
}
