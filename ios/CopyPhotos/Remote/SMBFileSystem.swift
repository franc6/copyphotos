import AMSMB2
import CopyCore
import CryptoKit
import Foundation

/// `RemoteFileSystem` over SMB 2/3 using AMSMB2 (libsmb2).
final class SMBFileSystem: RemoteFileSystem, @unchecked Sendable {
    private let manager: SMB2Manager

    init(manager: SMB2Manager) {
        self.manager = manager
    }

    private func smbPath(_ path: String) -> String {
        "/" + RemotePath.join(path)
    }

    /// True for "no such file or folder" errors.
    private static func isNotFound(_ error: Error) -> Bool {
        if let posix = error as? POSIXError { return posix.code == .ENOENT || posix.code == .ENOTDIR }
        let ns = error as NSError
        return ns.domain == NSPOSIXErrorDomain && (ns.code == Int(ENOENT) || ns.code == Int(ENOTDIR))
    }

    /// Size from an attributes dictionary, whichever integer type AMSMB2 used for it.
    private static func fileSize(_ attrs: [URLResourceKey: Any]) -> UInt64 {
        switch attrs[.fileSizeKey] {
        case let n as Int64: return UInt64(max(0, n))
        case let n as Int: return UInt64(max(0, n))
        case let n as NSNumber: return n.uint64Value
        default: return 0
        }
    }

    private static func isDirectory(_ attrs: [URLResourceKey: Any]) -> Bool {
        (attrs[.isDirectoryKey] as? Bool) == true
    }

    func ensureDirectory(_ path: String) async throws {
        var current = ""
        for comp in path.split(separator: "/").map(String.init) {
            current = RemotePath.join(current, comp)
            do {
                let attrs = try await manager.attributesOfItem(atPath: smbPath(current))
                if !Self.isDirectory(attrs) {
                    throw POSIXError(.ENOTDIR)
                }
            } catch where Self.isNotFound(error) {
                do {
                    try await manager.createDirectory(atPath: smbPath(current))
                } catch {
                    // Created concurrently? Accept if it now exists.
                    let attrs = try await manager.attributesOfItem(atPath: smbPath(current))
                    guard Self.isDirectory(attrs) else { throw error }
                }
            }
        }
    }

    func sizeOfItem(_ path: String) async throws -> UInt64? {
        do {
            let attrs = try await manager.attributesOfItem(atPath: smbPath(path))
            return Self.fileSize(attrs)
        } catch where Self.isNotFound(error) {
            return nil
        }
    }

    func upload(from localURL: URL, to path: String, progress: @escaping @Sendable (Int64) -> Void) async throws {
        let p = smbPath(path)
        do {
            // Exclusive create (O_EXCL): fails with EEXIST rather than touching an existing file.
            try await manager.uploadItem(at: localURL, toPath: p) { bytes in
                progress(bytes)
                return !Task.isCancelled
            }
            try Task.checkCancellation()
        } catch {
            // Remove what this upload wrote, but never a file that was already there.
            if (error as? POSIXError)?.code != .EEXIST {
                try? await manager.removeFile(atPath: p)
            }
            throw error
        }
    }

    /// Hashes each chunk inside AMSMB2's read callback. The next chunk is read only after the
    /// callback returns, so memory stays at one chunk, and returning false stops the read at once
    /// when the task is cancelled. (AMSMB2's AsyncThrowingStream variant buffers without limit
    /// and keeps reading the whole file after the consumer stops.)
    func readBackDigest(_ path: String) async throws -> FileDigest {
        let digest = StreamingDigest()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                manager.contents(atPath: smbPath(path), offset: 0, fetchedData: { _, _, data in
                    digest.update(data)
                }, completionHandler: { error in
                    if let error { cont.resume(throwing: error) } else { cont.resume() }
                })
            }
        } onCancel: {
            digest.cancel()
        }
        try Task.checkCancellation()
        return digest.result()
    }

    func move(_ from: String, to: String) async throws {
        // libsmb2 renames with ReplaceIfExists = 0: fails if `to` exists.
        try await manager.moveItem(atPath: smbPath(from), toPath: smbPath(to))
    }

    func removeFile(_ path: String) async throws {
        do {
            try await manager.removeFile(atPath: smbPath(path))
        } catch where Self.isNotFound(error) {}
    }

    func list(_ path: String) async throws -> [RemoteEntry] {
        let items: [[URLResourceKey: Any]]
        do {
            items = try await manager.contentsOfDirectory(atPath: smbPath(path))
        } catch where Self.isNotFound(error) {
            throw RemoteError.notFound
        }
        return items.compactMap { item in
            guard let name = item[.nameKey] as? String, name != ".", name != ".." else { return nil }
            return RemoteEntry(name: name, isDirectory: Self.isDirectory(item), size: Self.fileSize(item))
        }
    }

    func close() async {
        try? await manager.disconnectShare(gracefully: true)
    }
}

/// SHA-256 and byte count fed from AMSMB2's read callback, with a cancel flag the callback checks.
private final class StreamingDigest: @unchecked Sendable {
    private let lock = NSLock()
    private var hasher = SHA256()
    private var size: UInt64 = 0
    private var cancelled = false

    /// Adds a chunk. Returns false once cancelled, which tells AMSMB2 to stop reading.
    func update(_ data: Data) -> Bool {
        lock.withLock {
            guard !cancelled else { return false }
            hasher.update(data: data)
            size += UInt64(data.count)
            return true
        }
    }

    func cancel() { lock.withLock { cancelled = true } }

    func result() -> FileDigest {
        lock.withLock { FileDigest(sha256: Data(hasher.finalize()), size: size) }
    }
}
