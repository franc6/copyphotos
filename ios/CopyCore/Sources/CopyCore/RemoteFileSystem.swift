import CryptoKit
import Foundation

public struct RemoteEntry: Sendable, Equatable, Identifiable {
    public var name: String
    public var isDirectory: Bool
    public var size: UInt64
    public var id: String { name }

    public init(name: String, isDirectory: Bool, size: UInt64) {
        self.name = name
        self.isDirectory = isDirectory
        self.size = size
    }
}

/// SHA-256 and byte count of a file's contents.
public struct FileDigest: Sendable, Equatable {
    public var sha256: Data
    public var size: UInt64

    public init(sha256: Data, size: UInt64) {
        self.sha256 = sha256
        self.size = size
    }

    public var hex: String { sha256.map { String(format: "%02x", $0) }.joined() }

    /// Digest of a local file, streamed in 1 MiB chunks.
    public init(contentsOf url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var size: UInt64 = 0
        while true {
            let chunk = try autoreleasepool { try handle.read(upToCount: 1 << 20) ?? Data() }
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
            size += UInt64(chunk.count)
        }
        self.init(sha256: Data(hasher.finalize()), size: size)
    }
}

public enum RemoteError: Error, Equatable {
    /// No file or folder at the path.
    case notFound
}

/// A connected share. All paths are relative to the share/export root, use
/// "/" as the separator and have no leading slash ("" is the root).
public protocol RemoteFileSystem: AnyObject, Sendable {
    /// Creates the directory and any missing parents.
    func ensureDirectory(_ path: String) async throws
    /// Size of a regular file, or nil if nothing exists at `path`.
    func sizeOfItem(_ path: String) async throws -> UInt64?
    /// Writes a local file directly to `path`, which must not exist yet (exclusive create, so an
    /// existing file is never touched). If writing fails after the file was created, the
    /// implementation removes the file it created.
    func upload(from localURL: URL, to path: String, progress: @escaping @Sendable (Int64) -> Void) async throws
    /// Reads the remote file back fully and returns its SHA-256 and byte count.
    func readBackDigest(_ path: String) async throws -> FileDigest
    /// Moves a file within the share. Never replaces: fails if `to` already exists.
    func move(_ from: String, to: String) async throws
    func removeFile(_ path: String) async throws
    /// Contents of a folder. Throws `RemoteError.notFound` if the folder does not exist.
    func list(_ path: String) async throws -> [RemoteEntry]
    func close() async
}

public enum RemotePath {
    /// Joins path components into one share-relative path that can't climb above the share.
    /// - Parameter parts: Path components, each of which may itself contain `/` or `\`.
    /// - Returns: The path, `/`-separated, without empty, `.` or `..` segments.
    public static func join(_ parts: String...) -> String { join(parts) }

    /// Joins path components into one share-relative path that can't climb above the share.
    /// - Parameter parts: Path components, each of which may itself contain `/` or `\`.
    /// - Returns: The path, `/`-separated, without empty, `.` or `..` segments.
    public static func join(_ parts: [String]) -> String {
        parts.flatMap { $0.split(whereSeparator: { $0 == "/" || $0 == "\\" }).map(String.init) }
            .filter { !$0.isEmpty && $0 != "." && $0 != ".." }
            .joined(separator: "/")
    }

    public static func parent(_ path: String) -> String {
        var comps = path.split(separator: "/").map(String.init)
        if !comps.isEmpty { comps.removeLast() }
        return comps.joined(separator: "/")
    }

    public static func name(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? ""
    }

    /// Makes a file name safe for SMB (Windows rules).
    public static func sanitizeFileName(_ name: String) -> String {
        let illegal = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.controlCharacters)
        var cleaned = String(name.unicodeScalars.map { illegal.contains($0) ? "_" : Character($0) })
        cleaned = cleaned.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        if cleaned.isEmpty { cleaned = "photo" }
        return cleaned
    }

    /// "IMG_1.JPG" -> "IMG_1"
    public static func stem(_ name: String) -> String { (name as NSString).deletingPathExtension }

    /// "IMG_1.JPG" -> "JPG"
    public static func fileExtension(_ name: String) -> String { (name as NSString).pathExtension }

    public static func withExtension(_ stem: String, _ ext: String) -> String { ext.isEmpty ? stem : "\(stem).\(ext)" }

    /// "IMG_1.JPG", n=2 -> "IMG_1_2.JPG"
    public static func numbered(_ name: String, _ n: Int) -> String {
        guard n > 0 else { return name }
        return withExtension("\(stem(name))_\(n)", fileExtension(name))
    }
}

#if !canImport(ObjectiveC)
@inline(__always) func autoreleasepool<T>(_ body: () throws -> T) rethrows -> T { try body() }
#endif
