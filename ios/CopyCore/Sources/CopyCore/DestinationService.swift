import Foundation
import Observation

/// Where the destination's settings and password are saved. Implemented by the platform
/// (UserDefaults and the Keychain); only `DestinationService` uses it.
public protocol DestinationStore {
    /// The saved settings.
    /// - Returns: The settings, or defaults if none are saved.
    func loadConfig() -> DestinationConfig
    /// Saves the settings.
    /// - Parameter config: The settings to save.
    func saveConfig(_ config: DestinationConfig)
    /// Whether a password is saved.
    var hasPassword: Bool { get }
    /// Saves the password; an empty one removes it.
    /// - Parameter password: The new password.
    /// - Throws: When the secure store refuses it.
    func setPassword(_ password: String) throws
}

/// Opens connections to a destination. Implemented by the SMB adapter, which reads the
/// password from the secure store itself.
public protocol RemoteConnector {
    /// Connects to the share root (not to `config.path`).
    /// - Parameter config: The destination to connect to.
    /// - Returns: The share's file system.
    func open(_ config: DestinationConfig) async throws -> RemoteFileSystem
    /// The shares on a server, as the server lists them.
    /// - Parameter config: The destination whose host and sign-in to use.
    /// - Returns: The shares.
    func listShares(_ config: DestinationConfig) async throws -> [RemoteEntry]
}

/// The destination and everything done with it: its settings, saving its password, testing it,
/// browsing it for a folder, and connecting to it for a copy. Nothing else reads the settings
/// store.
@MainActor
@Observable
public final class DestinationService {
    /// The saved settings, for the screen that edits them. Everything else asks the service.
    public private(set) var config: DestinationConfig
    public private(set) var hasPassword: Bool

    @ObservationIgnored private let store: DestinationStore
    @ObservationIgnored private let connector: RemoteConnector
    @ObservationIgnored private var connections: [String: RemoteFileSystem] = [:]

    public init(store: DestinationStore, connector: RemoteConnector) {
        self.store = store
        self.connector = connector
        config = store.loadConfig()
        hasPassword = store.hasPassword
    }

    /// There is somewhere to copy to.
    public var isReady: Bool { config.isComplete }

    /// "smb://host/share/folder".
    public var displayURL: String { config.displayURL }

    /// Changes the settings and saves them. Returns false if nothing changed.
    @discardableResult
    public func update(_ change: (inout DestinationConfig) -> Void) -> Bool {
        var new = config
        change(&new)
        guard new != config else { return false }
        config = new
        store.saveConfig(new)
        return true
    }

    /// Saves the password; an empty one removes it.
    /// - Parameter password: The new password.
    /// - Returns: False if the secure store refused it.
    @discardableResult
    public func setPassword(_ password: String) -> Bool {
        defer { hasPassword = store.hasPassword }
        do {
            try store.setPassword(password)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Copying

    /// Where and how a copy writes.
    public var copyOptions: CopyOptions { config.copyOptions }

    /// Connects to the configured share. Throws `DestinationError.incomplete` if it isn't set up.
    public func connect() async throws -> RemoteFileSystem {
        try await open(config)
    }

    /// Connects and proves the destination folder is writable (see `DestinationProbe`).
    public func test() async -> ProbeResult {
        let config = config
        do {
            let fs = try await open(config)
            defer { Task { await fs.close() } }
            try await DestinationProbe.check(fs, basePath: config.path)
            return .succeeded
        } catch {
            return .failed(FailureReason(error))
        }
    }

    private func open(_ config: DestinationConfig) async throws -> RemoteFileSystem {
        guard config.isComplete else { throw DestinationError.incomplete }
        return try await connector.open(config)
    }

    // MARK: - Location browser

    /// Where the browser opens: the folder already chosen, with its parents on the back stack.
    public func browseStack() -> [BrowseLocation] { BrowseLocation.stack(for: config) }

    /// The server's shares (`location` nil) or the contents of a folder.
    public func listing(for location: BrowseLocation?) async -> BrowseListing {
        do {
            guard let location else {
                guard !config.host.isEmpty else { throw DestinationError.invalidHost }
                let shares = try await connector.listShares(config)
                return .entries(RemoteEntry.browsableShares(shares))
            }
            return .entries(RemoteEntry.browsableEntries(try await fileSystem(for: location.share).list(location.path)))
        } catch RemoteError.notFound {
            return .missing
        } catch {
            if let share = location?.share { dropConnection(share) }
            return .failed(FailureReason(error))
        }
    }

    /// Creates a folder in `location` and returns it.
    public func createFolder(_ name: String, in location: BrowseLocation) async throws -> BrowseLocation {
        let child = location.child(RemotePath.sanitizeFileName(name))
        try await fileSystem(for: location.share).ensureDirectory(child.path)
        return child
    }

    /// Sets the share and the folder together, and ends browsing. Returns false if nothing changed.
    @discardableResult
    public func choose(_ location: BrowseLocation) -> Bool {
        defer { endBrowsing() }
        return update {
            $0.share = location.share
            $0.path = location.path
        }
    }

    /// "smb://host/share/folder" for a place in the browser (the server itself for nil).
    public func displayURL(for location: BrowseLocation?) -> String {
        config.url(share: location?.share, path: location?.path ?? "")
    }

    /// Closes the browser's connections.
    public func endBrowsing() {
        let all = Array(connections.values)
        connections.removeAll()
        Task { for fs in all { await fs.close() } }
    }

    private func fileSystem(for share: String) async throws -> RemoteFileSystem {
        if let fs = connections[share] { return fs }
        var c = config
        c.share = share
        let fs = try await open(c)
        connections[share] = fs
        return fs
    }

    private func dropConnection(_ share: String) {
        guard let fs = connections.removeValue(forKey: share) else { return }
        Task { await fs.close() }
    }
}

/// The outcome of testing a destination with `DestinationProbe`.
public enum ProbeResult: Sendable, Equatable {
    case succeeded
    case failed(FailureReason)
}

/// Proves a destination is writable: creates the folder, then writes, reads back and deletes a
/// small probe file. The probe lives in the staging folder, so nothing appears in the folder itself.
public enum DestinationProbe {
    public static func check(_ fs: RemoteFileSystem, basePath: String) async throws {
        let base = RemotePath.join(basePath)
        try await fs.ensureDirectory(base)
        let staging = RemotePath.join(base, CopyEngine.stagingFolder)
        try await fs.ensureDirectory(staging)
        let probe = RemotePath.join(staging, ".copyphotos-probe-\(UUID().uuidString.prefix(8))")
        let local = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let payload = Data("CopyPhotos write test \(Date())".utf8)
        try payload.write(to: local)
        defer { try? FileManager.default.removeItem(at: local) }
        try await fs.upload(from: local, to: probe) { _ in }
        let digest: FileDigest
        do {
            digest = try await fs.readBackDigest(probe)
        } catch {
            try? await fs.removeFile(probe)
            throw error
        }
        try? await fs.removeFile(probe)
        guard digest.size == UInt64(payload.count) else {
            throw CopyError.sizeMismatch(local: UInt64(payload.count), remote: digest.size)
        }
    }
}
