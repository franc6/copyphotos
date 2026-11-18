import AMSMB2
import CopyCore
import Foundation

/// Connects to SMB servers with AMSMB2 (libsmb2), for `DestinationService`. Connect and
/// sign-in failures become `DestinationError`s here, so no SMB detail leaves `Remote`.
struct SMBConnector: RemoteConnector {
    /// Where the password is saved; read only while signing in.
    let keychain: Keychain

    /// A manager for the server in `config`, signed in as `config` says.
    /// - Parameter config: The destination's host, port and sign-in.
    /// - Returns: The manager, not yet connected.
    private func makeManager(_ config: DestinationConfig) throws -> SMB2Manager {
        var comps = URLComponents()
        comps.scheme = "smb"
        comps.host = config.host
        comps.port = config.port
        guard let url = comps.url else { throw DestinationError.invalidHost }
        let credential: URLCredential? = config.smbAuth == .guest
            ? nil
            : URLCredential(user: config.username, password: keychain.password(), persistence: .forSession)
        guard let manager = SMB2Manager(url: url, domain: config.domain, credential: credential) else {
            throw DestinationError.invalidHost
        }
        manager.timeout = 30
        return manager
    }

    /// Connects to the share root.
    /// - Parameter config: The destination to connect to.
    /// - Returns: The share's file system.
    func open(_ config: DestinationConfig) async throws -> RemoteFileSystem {
        let manager = try makeManager(config)
        do {
            try await manager.connectShare(name: config.share, encrypted: config.encrypted)
        } catch {
            throw Self.destinationError(error, host: config.host, share: config.share)
        }
        return SMBFileSystem(manager: manager)
    }

    /// The disk shares on a server (connects to IPC$). AMSMB2 always lists shares without
    /// encryption; only share names cross the network, and the sign-in is NTLMv2 either way.
    func listShares(_ config: DestinationConfig) async throws -> [RemoteEntry] {
        let manager = try makeManager(config)
        defer { Task { try? await manager.disconnectShare() } }
        do {
            return try await manager.listShares().map { RemoteEntry(name: $0.name, isDirectory: true, size: 0) }
        } catch {
            throw Self.destinationError(error, host: config.host, share: nil)
        }
    }

    /// A connect or sign-in failure as a `DestinationError`, so no SMB detail leaves this file.
    /// When a sign-in or share connection fails, libsmb2 often reports only "-1", which AMSMB2
    /// turns into "Error code 1:" with no text, and the server's actual status is lost. Most
    /// other failures arrive as plain POSIX codes.
    /// - Parameters:
    ///   - error: The error AMSMB2 threw.
    ///   - host: The server.
    ///   - share: The share being connected, or nil while listing shares.
    /// - Returns: The matching `DestinationError`.
    private static func destinationError(_ error: Error, host: String, share: String?) -> DestinationError {
        let ns = error as NSError
        let text = ns.localizedDescription.trimmingCharacters(in: .whitespaces)
        let detail = text.isEmpty || text.hasSuffix(":") ? nil : text
        let other = DestinationError.other(host: host, code: ns.code, detail: detail)
        guard ns.domain == NSPOSIXErrorDomain else { return other }
        if Int32(ns.code) == EPERM, detail == nil { return .signInRefused(host: host, share: share) }
        return posixError(Int32(ns.code), host: host, share: share) ?? other
    }

    /// The `DestinationError` a POSIX error code stands for when connecting.
    /// - Parameters:
    ///   - code: The POSIX error code.
    ///   - host: The server.
    ///   - share: The share being connected, or nil while listing shares.
    /// - Returns: The error, or nil if the code has no specific meaning here.
    private static func posixError(_ code: Int32, host: String, share: String?) -> DestinationError? {
        switch code {
        case ECONNREFUSED: return .connectionRefused(host: host)
        case EACCES: return .accessDenied(host: host, share: share)
        case ENOENT: return share.map { .shareMissing(host: host, share: $0) }
        case ETIMEDOUT: return .timedOut(host: host)
        case EHOSTUNREACH, ENETUNREACH, EHOSTDOWN: return .unreachable(host: host)
        default: return nil
        }
    }
}
