import Foundation

public enum VerifyMode: String, Codable, Sendable, CaseIterable {
    /// Read the uploaded file back and compare SHA-256 with the local file (strongest).
    case readBackChecksum
    /// Compare the size reported by the server with the local size (faster).
    case sizeOnly
}

/// Where and how to copy.
public struct CopyOptions: Sendable {
    /// Identifies the destination in copy records, e.g. "smb://nas/Photos".
    public var destinationKey: String
    /// Folder inside the share that files are copied into.
    public var basePath: String
    public var verifyMode: VerifyMode

    public init(destinationKey: String, basePath: String, verifyMode: VerifyMode = .readBackChecksum) {
        self.destinationKey = destinationKey
        self.basePath = basePath
        self.verifyMode = verifyMode
    }
}

/// A photo or video to copy. The source exports all of its files to local temporary files.
public struct CopyItem: Sendable, Hashable {
    public var id: String
    public var fileName: String
    public var creationDate: Date?

    public init(id: String, fileName: String, creationDate: Date?) {
        self.id = id
        self.fileName = fileName
        self.creationDate = creationDate
    }
}

/// One local file belonging to an item (e.g. the original, the edited version, the Live Photo video).
public struct ExportedFile: Sendable {
    public var localURL: URL
    /// File name to use at the destination, before any conflict numbering.
    public var fileName: String

    public init(localURL: URL, fileName: String) {
        self.localURL = localURL
        self.fileName = fileName
    }
}

public protocol CopySource: Sendable {
    /// Writes every file that makes up the item to temporary local files. The engine deletes them afterwards.
    /// File names must be distinct.
    func export(_ item: CopyItem) async throws -> [ExportedFile]
}

/// What a file is, relative to the item it belongs to.
public enum AssetFileRole: Sendable, Equatable {
    case original
    /// Second original of a pair, e.g. the RAW of a RAW+JPEG.
    case alternateOriginal
    case livePhotoVideo
    case edited
    case editedLivePhotoVideo
    /// The edit recipe (Apple's .AAE sidecar).
    case adjustments
}

/// Names the files of one item so they sort and pair together, following the names
/// iOS itself uses when importing to a computer: IMG_0001.HEIC, IMG_0001.MOV,
/// IMG_E0001.HEIC (edited), IMG_E0001.MOV (edited Live Photo video), IMG_0001.AAE.
public enum AssetFileNaming {
    public static func names(base baseFileName: String, files: [(role: AssetFileRole, originalFilename: String)]) -> [String] {
        let stem = RemotePath.stem(baseFileName)
        let editedStem: String
        if stem.uppercased().hasPrefix("IMG_") {
            editedStem = String(stem.prefix(4)) + "E" + stem.dropFirst(4)
        } else {
            editedStem = stem + "_edited"
        }
        var used = Set<String>()
        return files.map { f in
            let ext = RemotePath.fileExtension(f.originalFilename)
            let name: String
            switch f.role {
            case .original, .alternateOriginal, .livePhotoVideo: name = RemotePath.withExtension(stem, ext)
            case .edited, .editedLivePhotoVideo: name = RemotePath.withExtension(editedStem, ext)
            case .adjustments: name = stem + ".AAE"
            }
            var unique = name
            var n = 2
            while used.contains(unique.lowercased()) {
                unique = RemotePath.numbered(name, n)
                n += 1
            }
            used.insert(unique.lowercased())
            return unique
        }
    }
}

public struct CopyProgress: Sendable {
    public var completed: Int
    public var failed: Int
    public var total: Int
    public var currentName: String?
    public var currentBytes: Int64

    public init(completed: Int, failed: Int, total: Int, currentName: String?, currentBytes: Int64) {
        self.completed = completed
        self.failed = failed
        self.total = total
        self.currentName = currentName
        self.currentBytes = currentBytes
    }
}

public struct CopySummary: Sendable {
    public var copied: [CopyRecord] = []
    public var alreadyPresent: [CopyRecord] = []
    public var failures: [CopyFailure] = []
    public var cancelled = false
}

public struct CopyOutcome: Sendable, Equatable {
    public var record: CopyRecord
    /// Identical files were already at the destination, so nothing was uploaded.
    public var alreadyPresent: Bool
}

/// Copies items one by one with verification:
///  1. export every file of the item to temp files and hash them,
///  2. upload each new file into the `.upload` folder and verify it there
///     (read back + SHA-256, or size),
///  3. once every file of the item is verified, move them into place (never replacing anything),
///  4. record the item in the ledger.
/// A photo is recorded only after all of this succeeds.
public final class CopyEngine: @unchecked Sendable {
    /// Folder inside the destination path where files are uploaded and verified before
    /// being moved into place.
    public static let stagingFolder = ".upload"

    private let fs: RemoteFileSystem
    private let source: CopySource
    private let ledger: any Ledger
    private let options: CopyOptions

    public init(fs: RemoteFileSystem, source: CopySource, ledger: any Ledger, options: CopyOptions) {
        self.fs = fs
        self.source = source
        self.ledger = ledger
        self.options = options
    }

    public func copy(_ items: [CopyItem], progress: @escaping @Sendable (CopyProgress) -> Void) async -> CopySummary {
        var summary = CopySummary()
        var p = CopyProgress(completed: 0, failed: 0, total: items.count, currentName: nil, currentBytes: 0)
        progress(p)
        for item in items {
            if Task.isCancelled {
                summary.cancelled = true
                break
            }
            p.currentName = item.fileName
            p.currentBytes = 0
            progress(p)
            do {
                let snapshot = p
                let outcome = try await copyOne(item) { bytes in
                    var q = snapshot
                    q.currentBytes = bytes
                    progress(q)
                }
                if outcome.alreadyPresent {
                    summary.alreadyPresent.append(outcome.record)
                } else {
                    summary.copied.append(outcome.record)
                }
                p.completed += 1
            } catch is CancellationError {
                summary.cancelled = true
                break
            } catch {
                summary.failures.append(CopyFailure(fileName: item.fileName, reason: FailureReason(error)))
                p.failed += 1
            }
            progress(p)
        }
        p.currentName = nil
        progress(p)
        return summary
    }

    /// What to do with one file of an item.
    enum Step: Equatable {
        /// Upload the file to this path.
        case upload(target: String)
        /// An identical file is already at this path.
        case identical(target: String)
    }

    /// Copies all files of one item. The item is recorded only after every file is verified.
    /// An identical file already at the destination is never duplicated. A name taken by a
    /// different file gets `_<n>` appended; all files of an item share one number, so related
    /// files keep matching names (IMG_0001_1.HEIC goes with IMG_0001_1.MOV).
    /// - Parameters:
    ///   - item: The item to copy.
    ///   - progress: Receives the bytes uploaded so far for this item.
    /// - Returns: The item's record, and whether it was already at the destination.
    public func copyOne(_ item: CopyItem, progress: @escaping @Sendable (Int64) -> Void = { _ in }) async throws -> CopyOutcome {
        let exported = try await source.export(item)
        defer { exported.forEach { try? FileManager.default.removeItem(at: $0.localURL) } }
        guard !exported.isEmpty else { throw CopyError.nothingToCopy }
        let digests = try exported.map { try FileDigest(contentsOf: $0.localURL) }
        let names = exported.map { RemotePath.sanitizeFileName($0.fileName) }

        // Everything goes straight into the destination folder; no subfolders.
        let dir = RemotePath.join(options.basePath)
        try await fs.ensureDirectory(dir)

        let plan = try await planNames(names: names, digests: digests, in: dir)
        let staged = try await stage(plan, files: exported, digests: digests, progress: progress)
        let files = try await moveIntoPlace(plan, staged: staged, digests: digests)
        let uploadedAny = plan.contains { if case .upload = $0 { return true } else { return false } }

        let record = CopyRecord(assetID: item.id, destination: options.destinationKey, files: files, copiedAt: Date())
        try await ledger.add(record)
        return CopyOutcome(record: record, alreadyPresent: !uploadedAny)
    }

    /// Uploads and verifies every file the plan uploads, in the staging folder, so that nothing
    /// incomplete ever appears in the destination folder. On failure, nothing is left staged.
    /// - Parameters:
    ///   - plan: What to do with each file.
    ///   - files: The exported files, in plan order.
    ///   - digests: Their digests, in plan order.
    ///   - progress: Receives the bytes uploaded so far for the item.
    /// - Returns: The staged path of each uploaded file, by its index in the plan.
    private func stage(_ plan: [Step], files: [ExportedFile], digests: [FileDigest],
                       progress: @escaping @Sendable (Int64) -> Void) async throws -> [Int: String] {
        let staging = RemotePath.join(options.basePath, CopyEngine.stagingFolder)
        var staged: [Int: String] = [:]
        do {
            var done: Int64 = 0
            for (i, step) in plan.enumerated() {
                if case .upload(let target) = step {
                    if staged.isEmpty { try await fs.ensureDirectory(staging) }
                    // Unique name inside .upload, so leftovers from an interrupted run never block a later one.
                    let path = RemotePath.join(staging, "\(UUID().uuidString.prefix(8))-\(RemotePath.name(target))")
                    let base = done
                    try await upload(files[i].localURL, to: path, digest: digests[i]) { progress(base + $0) }
                    staged[i] = path
                }
                done += Int64(digests[i].size)
            }
            try Task.checkCancellation()
        } catch {
            await removeAll(staged.values)
            throw error
        }
        return staged
    }

    /// Moves every staged file to its target and checks its size there. On failure, files
    /// already moved stay (they are complete and verified) and the rest are removed.
    /// - Parameters:
    ///   - plan: What to do with each file.
    ///   - staged: The staged path of each uploaded file, by its index in the plan.
    ///   - digests: The files' digests, in plan order.
    /// - Returns: Every file of the item at the destination, in plan order.
    private func moveIntoPlace(_ plan: [Step], staged: [Int: String], digests: [FileDigest]) async throws -> [CopiedFile] {
        var staged = staged
        var files: [CopiedFile] = []
        for (i, step) in plan.enumerated() {
            let digest = digests[i]
            let target: String
            switch step {
            case .identical(let path):
                target = path
            case .upload(let path):
                target = path
                guard let stagedPath = staged.removeValue(forKey: i) else { continue }
                do {
                    try await fs.move(stagedPath, to: target)
                    guard try await fs.sizeOfItem(target) == digest.size else {
                        throw CopyError.wrongSizeAfterMove(path: target)
                    }
                } catch {
                    await removeAll([stagedPath] + staged.values)
                    throw error
                }
            }
            files.append(CopiedFile(remotePath: target, size: digest.size, sha256Hex: digest.hex))
        }
        return files
    }

    /// Chooses, for every file of an item, either an existing identical file or a free name.
    /// Candidates are `name`, `name_1`, `name_2`, … (matched case-insensitively, as SMB does).
    /// An identical copy of the whole item wins; otherwise the first number at which every file
    /// is identical or free is used. Existing files are never modified.
    /// - Parameters:
    ///   - names: The files' names, sanitized.
    ///   - digests: The files' digests, in the same order.
    ///   - dir: The destination folder.
    /// - Returns: What to do with each file, in the same order.
    func planNames(names: [String], digests: [FileDigest], in dir: String) async throws -> [Step] {
        let slots = try await NameSlots(fs: fs, dir: dir, names: names, digests: digests)
        let maxN = 1000
        for n in 0..<maxN {
            if let steps = try await slots.wholeItemIdentical(at: n) { return steps }
        }
        for n in 0..<maxN {
            if let steps = try await slots.usable(at: n) { return steps }
        }
        throw CopyError.tooManyNameConflicts(name: names[0])
    }

    /// Writes the file (to the staging folder), then verifies it. A file that fails
    /// verification was written by this upload, so it is removed rather than left corrupt.
    private func upload(_ local: URL, to target: String, digest: FileDigest,
                        progress: @escaping @Sendable (Int64) -> Void) async throws {
        try await fs.upload(from: local, to: target, progress: progress)
        do {
            try Task.checkCancellation()
            try await verify(target, against: digest)
        } catch {
            try? await fs.removeFile(target)
            throw error
        }
    }

    /// Best-effort removal of files this engine wrote to the staging folder.
    private func removeAll(_ paths: some Sequence<String>) async {
        for path in paths { try? await fs.removeFile(path) }
    }

    private func verify(_ path: String, against local: FileDigest) async throws {
        let size = local.size
        switch options.verifyMode {
        case .readBackChecksum:
            let remote = try await fs.readBackDigest(path)
            guard remote.size == size else { throw CopyError.sizeMismatch(local: size, remote: remote.size) }
            guard remote.sha256 == local.sha256 else { throw CopyError.checksumMismatch }
        case .sizeOnly:
            let remoteSize = try await fs.sizeOfItem(path)
            guard remoteSize == size else { throw CopyError.sizeMismatch(local: size, remote: remoteSize) }
        }
    }
}

/// The destination folder as seen while naming one item's files: which names exist, and which
/// of them hold a file identical to one of the item's.
private final class NameSlots {
    /// What number `n` means for one file.
    enum Slot {
        /// No file has that name.
        case free
        /// An identical file has that name, at this path.
        case identical(String)
        /// A different file has that name.
        case taken
    }

    private let fs: RemoteFileSystem
    private let dir: String
    private let names: [String]
    private let digests: [FileDigest]
    /// The folder's entries, keyed by lowercased name.
    private let existing: [String: [RemoteEntry]]
    /// Whether each path already compared is identical to the file it was compared with.
    private var verdicts: [String: Bool] = [:]

    /// Lists the folder.
    /// - Parameters:
    ///   - fs: The destination.
    ///   - dir: The destination folder.
    ///   - names: The item's file names, sanitized.
    ///   - digests: The item's file digests, in the same order.
    init(fs: RemoteFileSystem, dir: String, names: [String], digests: [FileDigest]) async throws {
        self.fs = fs
        self.dir = dir
        self.names = names
        self.digests = digests
        // Sizes come from sizeOfItem, not the listing, because some servers omit them in listings.
        var existing: [String: [RemoteEntry]] = [:]
        for e in try await fs.list(dir) { existing[e.name.lowercased(), default: []].append(e) }
        self.existing = existing
    }

    /// The steps if number `n` holds an identical copy of every file.
    /// - Parameter n: The number to try.
    /// - Returns: The steps, or nil if some file has no identical copy at `n`.
    func wholeItemIdentical(at n: Int) async throws -> [CopyEngine.Step]? {
        var steps: [CopyEngine.Step] = []
        for i in names.indices {
            guard case .identical(let path) = try await slot(i, n) else { return nil }
            steps.append(.identical(target: path))
        }
        return steps
    }

    /// The steps if every file is identical or free at number `n`.
    /// - Parameter n: The number to try.
    /// - Returns: The steps, or nil if a different file takes some name at `n`.
    func usable(at n: Int) async throws -> [CopyEngine.Step]? {
        var steps: [CopyEngine.Step] = []
        for i in names.indices {
            switch try await slot(i, n) {
            case .free: steps.append(.upload(target: RemotePath.join(dir, RemotePath.numbered(names[i], n))))
            case .identical(let path): steps.append(.identical(target: path))
            case .taken: return nil
            }
        }
        return steps
    }

    /// What number `n` means for file `i`.
    /// - Parameters:
    ///   - i: The file's index.
    ///   - n: The number.
    /// - Returns: Whether the name is free, holds an identical file, or holds a different one.
    private func slot(_ i: Int, _ n: Int) async throws -> Slot {
        let candidate = RemotePath.numbered(names[i], n)
        guard let entries = existing[candidate.lowercased()] else { return .free }
        for e in entries where !e.isDirectory {
            let path = RemotePath.join(dir, e.name)
            if try await isIdentical(path, digests[i]) { return .identical(path) }
        }
        return .taken
    }

    /// Whether the file at `path` has the same size and SHA-256 as `digest`. A file of a
    /// different size is never read.
    /// - Parameters:
    ///   - path: The remote file.
    ///   - digest: The local file's digest.
    /// - Returns: True if identical.
    private func isIdentical(_ path: String, _ digest: FileDigest) async throws -> Bool {
        if let v = verdicts[path] { return v }
        var v = false
        if try await fs.sizeOfItem(path) == digest.size {
            v = try await fs.readBackDigest(path) == digest
        }
        verdicts[path] = v
        return v
    }
}
