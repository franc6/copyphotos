import CryptoKit
import XCTest
@testable import CopyCore

// MARK: - Test doubles

enum TestFSError: Error { case notFound, exists }

final class MemoryFS: RemoteFileSystem, @unchecked Sendable {
    let lock = NSLock()
    var files: [String: Data] = [:]
    var dirs: Set<String> = [""]
    /// When set, uploads are corrupted (simulates a bad transfer).
    var corruptUploads = false
    /// When set, only uploads whose path contains this string are corrupted.
    var corruptPathContaining: String?
    /// Runs before each move (with the lock held), e.g. to simulate another device taking the name.
    var beforeMove: ((MemoryFS, _ to: String) -> Void)?
    /// When set, replaces what reading a file back returns.
    var readBackOverride: ((String) -> FileDigest)?

    func ensureDirectory(_ path: String) async throws {
        lock.lock(); defer { lock.unlock() }
        var cur = ""
        for c in path.split(separator: "/") {
            cur = RemotePath.join(cur, String(c))
            dirs.insert(cur)
        }
    }

    func sizeOfItem(_ path: String) async throws -> UInt64? {
        lock.lock(); defer { lock.unlock() }
        return files[path].map { UInt64($0.count) }
    }

    func upload(from localURL: URL, to path: String, progress: @escaping @Sendable (Int64) -> Void) async throws {
        var data = try Data(contentsOf: localURL)
        if corruptUploads || corruptPathContaining.map({ path.contains($0) }) == true, !data.isEmpty { data[0] ^= 0xFF }
        lock.lock(); defer { lock.unlock() }
        guard dirs.contains(RemotePath.parent(path)) else { throw TestFSError.notFound }
        guard files[path] == nil else { throw TestFSError.exists }
        files[path] = data
        progress(Int64(data.count))
    }

    /// Paths whose contents were read back (to check that different sizes are never read).
    var readBack: [String] = []

    func readBackDigest(_ path: String) async throws -> FileDigest {
        lock.lock(); defer { lock.unlock() }
        readBack.append(path)
        if let readBackOverride { return readBackOverride(path) }
        guard let d = files[path] else { throw TestFSError.notFound }
        return FileDigest(sha256: Data(SHA256.hash(data: d)), size: UInt64(d.count))
    }

    /// Every move, in order (from, to).
    var moves: [(String, String)] = []

    func move(_ from: String, to: String) async throws {
        lock.lock(); defer { lock.unlock() }
        beforeMove?(self, to)
        guard let d = files[from] else { throw TestFSError.notFound }
        guard files[to] == nil else { throw TestFSError.exists }
        guard dirs.contains(RemotePath.parent(to)) else { throw TestFSError.notFound }
        files[from] = nil
        files[to] = d
        moves.append((from, to))
    }

    func removeFile(_ path: String) async throws {
        lock.lock(); defer { lock.unlock() }
        files[path] = nil
    }

    func list(_ path: String) async throws -> [RemoteEntry] {
        lock.lock(); defer { lock.unlock() }
        let dir = RemotePath.join(path)
        guard dirs.contains(dir) else { throw RemoteError.notFound }
        return files.filter { RemotePath.parent($0.key) == dir }
            .map { RemoteEntry(name: RemotePath.name($0.key), isDirectory: false, size: 0) } // size omitted, like some servers
    }
    func close() async {}
}

struct MemorySource: CopySource {
    /// Item ID -> files (name, bytes). Single-file items can use `contents`.
    var files: [String: [(String, Data)]] = [:]
    var contents: [String: Data] = [:]

    func export(_ item: CopyItem) async throws -> [ExportedFile] {
        let list = files[item.id] ?? [(item.fileName, contents[item.id]!)]
        return try list.map { name, data in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try data.write(to: url)
            return ExportedFile(localURL: url, fileName: name)
        }
    }
}

/// The saved destination settings and password, in memory.
final class MemoryStore: DestinationStore {
    /// Thrown by `setPassword` when `refusesPassword` is set.
    struct Refused: Error {}

    var config = DestinationConfig()
    var savedPassword = ""
    /// Makes `setPassword` fail, as a secure store that refuses the change.
    var refusesPassword = false

    /// The saved settings.
    /// - Returns: `config`.
    func loadConfig() -> DestinationConfig { config }
    /// Saves the settings.
    /// - Parameter config: The settings to save.
    func saveConfig(_ config: DestinationConfig) { self.config = config }
    /// Whether a password is saved.
    var hasPassword: Bool { !savedPassword.isEmpty }
    /// Saves the password unless `refusesPassword` is set.
    /// - Parameter password: The new password.
    /// - Throws: `Refused` when `refusesPassword` is set.
    func setPassword(_ password: String) throws {
        if refusesPassword { throw Refused() }
        savedPassword = password
    }
}

/// Connects every share to `fs`, and remembers the destinations it was asked for.
final class MemoryConnector: RemoteConnector {
    let fs: MemoryFS
    var shares: [RemoteEntry] = []
    var opened: [DestinationConfig] = []

    /// - Parameter fs: The file system every share connects to.
    init(fs: MemoryFS = MemoryFS()) { self.fs = fs }

    /// Records `config` and connects to `fs`.
    /// - Parameter config: The destination asked for.
    /// - Returns: `fs`.
    func open(_ config: DestinationConfig) async throws -> RemoteFileSystem {
        opened.append(config)
        return fs
    }
    /// Records `config` and lists `shares`.
    /// - Parameter config: The destination asked for.
    /// - Returns: `shares`.
    func listShares(_ config: DestinationConfig) async throws -> [RemoteEntry] {
        opened.append(config)
        return shares
    }
}

/// A destination that is set up: smb://nas/Share, folder P.
func completeConfig() -> DestinationConfig {
    var config = DestinationConfig()
    config.host = "nas"; config.share = "Share"; config.path = "P"; config.username = "u"
    return config
}

func tempLedger() -> (CopyLedger, URL) {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ledger-\(UUID().uuidString).json")
    return (CopyLedger(fileURL: url), url)
}

// MARK: - Paths

final class PathTests: XCTestCase {
    func testJoinNeverClimbsAboveTheShare() {
        // Arrange
        let parts = ["../Photos", "2026\\..\\11", "..", "a.jpg"]

        // Act
        let path = RemotePath.join(parts)

        // Assert
        XCTAssertEqual(path, "Photos/2026/11/a.jpg")
    }

    func testJoinAndParent() {
        XCTAssertEqual(RemotePath.join("/Photos/", "", "2026/11", "a.jpg"), "Photos/2026/11/a.jpg")
        XCTAssertEqual(RemotePath.parent("Photos/a.jpg"), "Photos")
        XCTAssertEqual(RemotePath.parent("a.jpg"), "")
        XCTAssertEqual(RemotePath.name("Photos/a.jpg"), "a.jpg")
    }

    func testSanitizeAndNumbering() {
        XCTAssertEqual(RemotePath.sanitizeFileName("a:b*c?.jpg"), "a_b_c_.jpg")
        XCTAssertEqual(RemotePath.sanitizeFileName(" .. "), "photo")
        XCTAssertEqual(RemotePath.numbered("IMG_1.HEIC", 2), "IMG_1_2.HEIC")
        XCTAssertEqual(RemotePath.numbered("noext", 1), "noext_1")
        XCTAssertEqual(RemotePath.numbered("x.jpg", 0), "x.jpg")
    }
}

// MARK: - Engine & ledger

final class EngineTests: XCTestCase {
    let photo = CopyItem(id: "A1", fileName: "IMG_0001.HEIC", creationDate: Date(timeIntervalSince1970: 1_762_128_000))

    func testCopyVerifiesAndRecords() async throws {
        let fs = MemoryFS()
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "test", basePath: "Photos"))
        let outcome = try await engine.copyOne(photo)
        let r = outcome
        guard !r.alreadyPresent else { return XCTFail("expected copy") }
        XCTAssertEqual(r.record.files.map(\.remotePath).first, "Photos/IMG_0001.HEIC")
        XCTAssertEqual(fs.files["Photos/IMG_0001.HEIC"], Data("hello".utf8))
        XCTAssertEqual(Array(fs.files.keys), ["Photos/IMG_0001.HEIC"], "written directly under its final name")
        let copied = await ledger.copiedIDs().contains("A1")
        XCTAssertTrue(copied)
    }

    func testCorruptUploadIsRejectedAndNotRecorded() async throws {
        let fs = MemoryFS()
        fs.corruptUploads = true
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "test", basePath: "Photos"))
        do {
            _ = try await engine.copyOne(photo)
            XCTFail("expected verification failure")
        } catch let e as CopyError {
            XCTAssertEqual(e, .checksumMismatch)
        }
        XCTAssertTrue(fs.files.isEmpty, "a file that fails verification is removed")
        let copied = await ledger.copiedIDs().contains("A1")
        XCTAssertFalse(copied)
    }

    func testIdenticalExistingFileIsNotDuplicated() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("Photos")
        fs.files["Photos/IMG_0001.HEIC"] = Data("hello".utf8)
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "test", basePath: "Photos"))
        let outcome = try await engine.copyOne(photo)
        XCTAssertTrue(outcome.alreadyPresent)
        XCTAssertEqual(fs.files.count, 1)
    }

    func testDifferentExistingFileKeepsBoth() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("Photos")
        fs.files["Photos/IMG_0001.HEIC"] = Data("other".utf8)
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "test", basePath: "Photos"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["Photos/IMG_0001_1.HEIC"])
        XCTAssertEqual(fs.files["Photos/IMG_0001.HEIC"], Data("other".utf8), "existing file must be untouched")
    }

    func testDifferentSizeIsDifferentAndNeverRead() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("P")
        fs.files["P/IMG_0001.HEIC"] = Data("longer content".utf8)
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001_1.HEIC"])
        XCTAssertFalse(fs.readBack.contains("P/IMG_0001.HEIC"), "a file of a different size must not be read")
    }

    func testSameSizeDifferentContentIsDifferent() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("P")
        fs.files["P/IMG_0001.HEIC"] = Data("HELLO".utf8) // same size, different bytes
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001_1.HEIC"])
        XCTAssertEqual(fs.files["P/IMG_0001.HEIC"], Data("HELLO".utf8))
    }

    func testIdenticalAtLaterNumberIsNotDuplicated() async throws {
        // IMG_0001 is different, _1 is free (e.g. deleted), _2 is identical: nothing is written.
        let fs = MemoryFS()
        try await fs.ensureDirectory("P")
        fs.files["P/IMG_0001.HEIC"] = Data("other".utf8)
        fs.files["P/IMG_0001_2.HEIC"] = Data("hello".utf8)
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard r.alreadyPresent else { return XCTFail("expected alreadyPresent") }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001_2.HEIC"])
        XCTAssertEqual(fs.files.count, 2)
    }

    func testIdenticalNameDifferingOnlyInCaseIsNotDuplicated() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("P")
        fs.files["P/img_0001.heic"] = Data("hello".utf8)
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard r.alreadyPresent else { return XCTFail("expected alreadyPresent") }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/img_0001.heic"])
    }

    func testRecopyAfterDestinationDeletion() async throws {
        // Deleting from the destination never touches the device; the user can copy again.
        let fs = MemoryFS()
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        _ = try await engine.copyOne(photo)
        fs.files.removeAll()
        let stillCopied = await ledger.copiedIDs().contains("A1")
        XCTAssertTrue(stillCopied, "destination changes do not affect the device-side record")
        let again = try await engine.copyOne(photo)
        XCTAssertFalse(again.alreadyPresent)
        XCTAssertEqual(fs.files.count, 1)
    }

    func testLivePhotoWithEditsCopiesEveryFile() async throws {
        let fs = MemoryFS()
        let (ledger, _) = tempLedger()
        let group: [(String, Data)] = [
            ("IMG_0001.HEIC", Data("orig".utf8)), ("IMG_0001.MOV", Data("video".utf8)),
            ("IMG_E0001.HEIC", Data("edited".utf8)), ("IMG_E0001.MOV", Data("edited video".utf8)),
            ("IMG_0001.AAE", Data("recipe".utf8)),
        ]
        let engine = CopyEngine(fs: fs, source: MemorySource(files: ["A1": group]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.count, 5)
        XCTAssertEqual(Set(fs.files.keys), Set(group.map { "P/\($0.0)" }))
        XCTAssertEqual(fs.files["P/IMG_E0001.MOV"], Data("edited video".utf8))
    }

    func testGroupSharesOneConflictNumber() async throws {
        let fs = MemoryFS()
        try await fs.ensureDirectory("P")
        fs.files["P/IMG_0001.MOV"] = Data("someone else's".utf8) // only the video name is taken
        let (ledger, _) = tempLedger()
        let source = MemorySource(files: ["A1": [("IMG_0001.HEIC", Data("p".utf8)), ("IMG_0001.MOV", Data("v".utf8))]])
        let engine = CopyEngine(fs: fs, source: source, ledger: ledger, options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001_1.HEIC", "P/IMG_0001_1.MOV"])
    }

    func testPartialFailureIsNotRecorded() async throws {
        let fs = MemoryFS()
        let (ledger, _) = tempLedger()
        fs.corruptPathContaining = ".MOV"
        let source = MemorySource(files: ["A1": [("IMG_0001.HEIC", Data("p".utf8)), ("IMG_0001.MOV", Data("v".utf8))]])
        let engine = CopyEngine(fs: fs, source: source, ledger: ledger, options: CopyOptions(destinationKey: "t", basePath: "P"))
        do { _ = try await engine.copyOne(photo); XCTFail("expected failure") } catch {}
        let copied = await ledger.copiedIDs().contains("A1")
        XCTAssertFalse(copied, "an item is only recorded when every file verified")
        XCTAssertTrue(fs.files.isEmpty, "nothing is moved into place unless every file verified; .upload is cleaned up")
        // Retrying reuses the already-verified photo and only uploads the video.
        fs.corruptPathContaining = nil
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001.HEIC", "P/IMG_0001.MOV"])
    }

    func testFilesAreStagedInUploadFolderThenMoved() async throws {
        let fs = MemoryFS()
        let (ledger, _) = tempLedger()
        let source = MemorySource(files: ["A1": [("IMG_0001.HEIC", Data("p".utf8)), ("IMG_0001.MOV", Data("v".utf8))]])
        let engine = CopyEngine(fs: fs, source: source, ledger: ledger, options: CopyOptions(destinationKey: "t", basePath: "P"))
        let r = try await engine.copyOne(photo)
        guard !r.alreadyPresent else { return XCTFail() }
        XCTAssertEqual(r.record.files.map(\.remotePath), ["P/IMG_0001.HEIC", "P/IMG_0001.MOV"], "straight into the destination folder")
        XCTAssertEqual(fs.moves.count, 2)
        XCTAssertTrue(fs.moves.allSatisfy { $0.0.hasPrefix("P/.upload/") }, "uploads go to P/.upload first")
        XCTAssertEqual(Set(fs.moves.map(\.1)), Set(r.record.files.map(\.remotePath)))
        XCTAssertEqual(Set(fs.files.keys), Set(r.record.files.map(\.remotePath)), ".upload is left empty")
    }

    func testNameTakenBeforeMoveFailsWithoutReplacing() async throws {
        // Simulates another device writing the same name between planning and moving.
        let fs = MemoryFS()
        fs.beforeMove = { fs, to in if fs.files[to] == nil { fs.files[to] = Data("someone else".utf8) } }
        let (ledger, _) = tempLedger()
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["A1": Data("hello".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        do { _ = try await engine.copyOne(photo); XCTFail("expected failure") } catch {}
        XCTAssertEqual(fs.files, ["P/IMG_0001.HEIC": Data("someone else".utf8)], "the other file is untouched, staging cleaned up")
        let copied = await ledger.copiedIDs().contains("A1")
        XCTAssertFalse(copied)
    }

    func testNaming() {
        let names = AssetFileNaming.names(base: "IMG_0001.HEIC", files: [
            (.original, "IMG_0001.HEIC"), (.alternateOriginal, "IMG_0001.DNG"), (.livePhotoVideo, "IMG_0001.MOV"),
            (.edited, "FullSizeRender.jpg"), (.editedLivePhotoVideo, "FullSizeRender.mov"), (.adjustments, "Adjustments.plist"),
        ])
        XCTAssertEqual(names, ["IMG_0001.HEIC", "IMG_0001.DNG", "IMG_0001.MOV", "IMG_E0001.jpg", "IMG_E0001.mov", "IMG_0001.AAE"])
        XCTAssertEqual(AssetFileNaming.names(base: "Screenshot.PNG", files: [(.original, "Screenshot.PNG"), (.edited, "x.PNG")]),
                       ["Screenshot.PNG", "Screenshot_edited.PNG"])
        XCTAssertEqual(AssetFileNaming.names(base: "a.jpg", files: [(.original, "a.jpg"), (.alternateOriginal, "b.jpg")]),
                       ["a.jpg", "a_2.jpg"])
    }

    func testLedgerPersistsAndPrunes() async throws {
        let (ledger, url) = tempLedger()
        try await ledger.add(CopyRecord(assetID: "A", destination: "d", files: [CopiedFile(remotePath: "a", size: 1, sha256Hex: "x")], copiedAt: Date()))
        try await ledger.add(CopyRecord(assetID: "B", destination: "d", files: [CopiedFile(remotePath: "b", size: 1, sha256Hex: "y")], copiedAt: Date()))
        let reloaded = CopyLedger(fileURL: url)
        let n = await reloaded.copiedIDs().count
        XCTAssertEqual(n, 2)
        let removed = try await reloaded.prune(keeping: ["A"])
        XCTAssertEqual(removed, 1)
        let ids = await CopyLedger(fileURL: url).copiedIDs()
        XCTAssertEqual(ids, ["A"])
        try await reloaded.clear()
        let cleared = await CopyLedger(fileURL: url).copiedIDs()
        XCTAssertEqual(cleared, [])
    }

    func testAddAppendsInsteadOfRewritingTheSnapshot() async throws {
        let (ledger, url) = tempLedger()
        try await ledger.add(ledgerRecord("A"))
        try await ledger.remove(["A"]) // writes a snapshot
        let snapshot = try Data(contentsOf: url)
        for id in ["B", "C", "D"] { try await ledger.add(ledgerRecord(id)) }
        XCTAssertEqual(try Data(contentsOf: url), snapshot)
        let ids = await CopyLedger(fileURL: url).copiedIDs()
        XCTAssertEqual(ids, ["B", "C", "D"])
    }

    func testLogFromAnotherGenerationIsIgnored() async throws {
        let (ledger, url) = tempLedger()
        try await ledger.add(ledgerRecord("A"))
        // A crash after the new snapshot was written but before the old log was removed.
        let oldLog = url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".0.log")
        let leftover = try Data(contentsOf: oldLog)
        try await ledger.clear()
        try leftover.write(to: oldLog)
        let ids = await CopyLedger(fileURL: url).copiedIDs()
        XCTAssertEqual(ids, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldLog.path))
    }

    func testTruncatedLogLineIsSkipped() async throws {
        let (ledger, url) = tempLedger()
        try await ledger.add(ledgerRecord("A"))
        let log = url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".0.log")
        let handle = try FileHandle(forWritingTo: log)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("\n{\"assetID\":\"B\",\"dest".utf8)) // cut short by a crash
        try handle.close()
        let reloaded = CopyLedger(fileURL: url)
        try await reloaded.add(ledgerRecord("C"))
        let ids = await CopyLedger(fileURL: url).copiedIDs()
        XCTAssertEqual(ids, ["A", "C"])
    }
}


// MARK: - Camera metadata rules

final class CameraMetadataTests: XCTestCase {
    func testShutterSpeed() {
        XCTAssertEqual(CameraMetadata.shutterSpeed(1.0 / 120), .fraction(denominator: 120))
        XCTAssertEqual(CameraMetadata.shutterSpeed(0.0333333), .fraction(denominator: 30))
        XCTAssertEqual(CameraMetadata.shutterSpeed(0.5), .fraction(denominator: 2))
        XCTAssertEqual(CameraMetadata.shutterSpeed(0.8), .seconds(0.8))
        XCTAssertEqual(CameraMetadata.shutterSpeed(2.54), .seconds(2.5))
        XCTAssertNil(CameraMetadata.shutterSpeed(0))
    }

    func testExposureBias() {
        XCTAssertEqual(CameraMetadata.exposureBias(0.6666), 0.7)
        XCTAssertEqual(CameraMetadata.exposureBias(-1.3), -1.3)
        XCTAssertEqual(CameraMetadata.exposureBias(-0.04)?.sign, .plus, "no negative zero")
        XCTAssertNil(CameraMetadata.exposureBias(.nan))
    }

    func testMegapixelsAndVideoResolution() {
        XCTAssertEqual(CameraMetadata.megapixels(width: 4032, height: 3024), 12)
        XCTAssertEqual(CameraMetadata.megapixels(width: 8064, height: 6048), 49)
        XCTAssertEqual(CameraMetadata.megapixels(width: 640, height: 480), 0.3)
        XCTAssertEqual(CameraMetadata.megapixels(width: 3840, height: 2160), 8.3)
        XCTAssertNil(CameraMetadata.megapixels(width: 0, height: 10))
        XCTAssertEqual(CameraMetadata.videoResolution(width: 3840, height: 2160), .uhd4K)
        XCTAssertEqual(CameraMetadata.videoResolution(width: 1080, height: 1920), .lines(1080))
        XCTAssertEqual(CameraMetadata.videoResolution(width: 1280, height: 720), .lines(720))
        XCTAssertEqual(CameraMetadata.videoResolution(width: 7680, height: 4320), .uhd8K)
    }

    func testDMS() {
        XCTAssertEqual(CameraMetadata.dms(37.33173), .init(degrees: 37, minutes: 19, seconds: 54.2, isPositive: true))
        XCTAssertEqual(CameraMetadata.dms(-122.0310), .init(degrees: 122, minutes: 1, seconds: 51.6, isPositive: false))
        // Seconds that round up to 60 carry into the minutes and degrees.
        XCTAssertEqual(CameraMetadata.dms(10.999999), .init(degrees: 11, minutes: 0, seconds: 0, isPositive: true))
    }

    func testTimeZoneOffset() {
        XCTAssertEqual(CameraMetadata.timeZoneOffset("+02:00"), 7200)
        XCTAssertEqual(CameraMetadata.timeZoneOffset("-05:30"), -19800)
        XCTAssertEqual(CameraMetadata.timeZoneOffset("Z"), 0)
        XCTAssertNil(CameraMetadata.timeZoneOffset("   :  "))
        XCTAssertNil(CameraMetadata.timeZoneOffset("+2:00"))
    }

    func testTimeZoneOffsetOutOfRangeIsNil() {
        // Arrange
        let offsets = ["+19:00", "-02:60"]

        // Act
        let parsed = offsets.map(CameraMetadata.timeZoneOffset)

        // Assert
        XCTAssertEqual(parsed, [nil, nil])
    }

    func testCameraName() {
        XCTAssertEqual(CameraMetadata.cameraName(make: "Apple", model: "iPhone 15 Pro"), "Apple iPhone 15 Pro")
        XCTAssertEqual(CameraMetadata.cameraName(make: "Google", model: "Google Pixel 8"), "Google Pixel 8")
        XCTAssertEqual(CameraMetadata.cameraName(make: "samsung", model: "SM-S918B"), "Samsung SM-S918B")
        XCTAssertEqual(CameraMetadata.cameraName(make: " Canon ", model: nil), "Canon")
        XCTAssertEqual(CameraMetadata.cameraName(make: "", model: "X100V"), "X100V")
        XCTAssertNil(CameraMetadata.cameraName(make: " ", model: nil))
    }

    func testPlaceNameParts() {
        XCTAssertEqual(CameraMetadata.placeNameParts(locality: "Cupertino", subAdministrativeArea: "Santa Clara",
                                                     administrativeArea: "California", country: "USA", name: "1 Infinite Loop"),
                       ["Cupertino", "California"])
        XCTAssertEqual(CameraMetadata.placeNameParts(locality: nil, subAdministrativeArea: "Paris",
                                                     administrativeArea: nil, country: "France", name: nil),
                       ["Paris", "France"])
        XCTAssertEqual(CameraMetadata.placeNameParts(locality: "Monaco", subAdministrativeArea: nil,
                                                     administrativeArea: nil, country: "Monaco", name: nil), ["Monaco"])
        XCTAssertEqual(CameraMetadata.placeNameParts(locality: nil, subAdministrativeArea: nil,
                                                     administrativeArea: nil, country: nil, name: "Pacific Ocean"), ["Pacific Ocean"])
        XCTAssertEqual(CameraMetadata.placeNameParts(locality: nil, subAdministrativeArea: nil,
                                                     administrativeArea: nil, country: nil, name: nil), [])
    }

    func testThumbnailLooksFlat() {
        XCTAssertTrue(ThumbnailFit.looksFlat(Array(repeating: 240, count: 256)))
        XCTAssertTrue(ThumbnailFit.looksFlat((0..<256).map { 240 + Double($0 % 3) }))
        XCTAssertFalse(ThumbnailFit.looksFlat((0..<256).map { $0 % 2 == 0 ? 0 : 255 }))
        XCTAssertFalse(ThumbnailFit.looksFlat([]))
        XCTAssertEqual(ThumbnailFit.luma(red: 255, green: 255, blue: 255), 255, accuracy: 0.001)
    }
}


// MARK: - Copy service

struct MemoryLibrary: MediaLibrary {
    var items: [CopyItem]
    var fails = false
    struct ReadError: Error {}

    func allIDs() async throws -> Set<String> {
        if fails { throw ReadError() }
        return Set(items.map(\.id))
    }
    func copyItems(ids: [String]) async throws -> [CopyItem] { items.filter { ids.contains($0.id) } }
}

func ledgerRecord(_ id: String) -> CopyRecord {
    CopyRecord(assetID: id, destination: "d", files: [CopiedFile(remotePath: id, size: 1, sha256Hex: "x")], copiedAt: Date())
}

@MainActor
final class CopyServiceTests: XCTestCase {
    let new = CopyItem(id: "new", fileName: "c.jpg", creationDate: Date(timeIntervalSince1970: 300))
    let old = CopyItem(id: "old", fileName: "a.jpg", creationDate: Date(timeIntervalSince1970: 100))
    let mid = CopyItem(id: "mid", fileName: "b.jpg", creationDate: Date(timeIntervalSince1970: 200))

    func service(_ library: MemoryLibrary, _ ledger: CopyLedger, fs: MemoryFS = MemoryFS(),
                 config: DestinationConfig = completeConfig()) -> CopyService {
        let store = MemoryStore()
        store.config = config
        let destination = DestinationService(store: store, connector: MemoryConnector(fs: fs))
        return CopyService(library: library, source: MemorySource(contents: ["new": Data("n".utf8), "old": Data("o".utf8), "mid": Data("m".utf8)]),
                           ledger: ledger, destination: destination)
    }

    func testUncopiedItemsArePrunedAndOldestFirst() async throws {
        let (ledger, _) = tempLedger()
        try await ledger.add(ledgerRecord("mid"))
        try await ledger.add(ledgerRecord("gone")) // no longer on the device
        let s = service(MemoryLibrary(items: [new, mid, old]), ledger)
        let items = try await s.uncopiedItems()
        XCTAssertEqual(items.map(\.id), ["old", "new"])
        let copied = await ledger.copiedIDs()
        XCTAssertEqual(copied, ["mid"])
    }

    func testItemsByIDAreOldestFirstEvenIfCopied() async throws {
        let (ledger, _) = tempLedger()
        try await ledger.add(ledgerRecord("new"))
        let items = try await service(MemoryLibrary(items: [new, mid, old]), ledger).items(ids: ["new", "old"])
        XCTAssertEqual(items.map(\.id), ["old", "new"])
    }

    func testFailedLibraryReadDoesNotPrune() async throws {
        let (ledger, _) = tempLedger()
        try await ledger.add(ledgerRecord("A"))
        let ids = await service(MemoryLibrary(items: [], fails: true), ledger).copiedIDsOnDevice()
        XCTAssertEqual(ids, ["A"])
    }

    func testRecordDeletedAndForgetHistory() async throws {
        let (ledger, _) = tempLedger()
        try await ledger.add(ledgerRecord("A"))
        try await ledger.add(ledgerRecord("B"))
        let s = service(MemoryLibrary(items: []), ledger)
        try await s.recordDeleted(["A"])
        var ids = await ledger.copiedIDs()
        XCTAssertEqual(ids, ["B"])
        try await s.forgetHistory()
        ids = await ledger.copiedIDs()
        XCTAssertEqual(ids, [])
    }

    func testRunCopiesAndRecords() async throws {
        let (ledger, _) = tempLedger()
        let fs = MemoryFS()
        let s = service(MemoryLibrary(items: [old]), ledger, fs: fs)
        let summary = try await s.run([old]) { _ in }
        XCTAssertEqual(summary.copied.count, 1)
        XCTAssertEqual(summary.copied.first?.destination, "smb://nas/Share")
        XCTAssertNotNil(fs.files["P/a.jpg"])
    }

    func testRunRefusesIncompleteDestination() async throws {
        let (ledger, _) = tempLedger()
        do {
            _ = try await service(MemoryLibrary(items: [old]), ledger, config: DestinationConfig()).run([old]) { _ in }
            XCTFail("expected failure")
        } catch let e as DestinationError {
            XCTAssertEqual(e, .incomplete)
        }
    }

    func testFailuresAreRecordedAsReasons() async throws {
        let (ledger, _) = tempLedger()
        let fs = MemoryFS()
        fs.corruptUploads = true
        let engine = CopyEngine(fs: fs, source: MemorySource(contents: ["old": Data("o".utf8)]), ledger: ledger,
                                options: CopyOptions(destinationKey: "t", basePath: "P"))
        let summary = await engine.copy([old]) { _ in }
        XCTAssertEqual(summary.failures, [CopyFailure(fileName: "a.jpg", reason: .copy(.checksumMismatch))])
        XCTAssertEqual(FailureReason(RemoteError.notFound), .notFound)
        XCTAssertEqual(FailureReason(DestinationError.invalidHost), .destination(.invalidHost))
    }
}

final class DestinationProbeTests: XCTestCase {
    func testProbeWritesOnlyToStagingAndCleansUp() async throws {
        let fs = MemoryFS()
        try await DestinationProbe.check(fs, basePath: "Photos")
        XCTAssertTrue(fs.dirs.contains("Photos/.upload"))
        XCTAssertTrue(fs.files.isEmpty)
        XCTAssertTrue(fs.readBack.allSatisfy { $0.hasPrefix("Photos/.upload/") })
    }

    func testProbeFailsOnCorruptWrite() async throws {
        let fs = MemoryFS()
        fs.readBackOverride = { _ in FileDigest(sha256: Data(), size: 0) }
        do {
            try await DestinationProbe.check(fs, basePath: "P")
            XCTFail("expected failure")
        } catch let e as CopyError {
            guard case .sizeMismatch(_, remote: 0) = e else { return XCTFail("\(e)") }
        }
        XCTAssertTrue(fs.files.isEmpty)
    }
}

@MainActor
final class DestinationServiceTests: XCTestCase {
    func make(_ config: DestinationConfig = completeConfig(), fs: MemoryFS = MemoryFS())
        -> (DestinationService, MemoryStore, MemoryConnector) {
        let store = MemoryStore()
        store.config = config
        store.savedPassword = "pw"
        let connector = MemoryConnector(fs: fs)
        return (DestinationService(store: store, connector: connector), store, connector)
    }

    func testConnectOpensTheConfiguredDestination() async throws {
        // Arrange
        let (service, _, connector) = make()

        // Act
        _ = try await service.connect()

        // Assert
        XCTAssertEqual(connector.opened, [completeConfig()])
    }

    func testRefusedPasswordIsReported() {
        // Arrange
        let (service, store, _) = make()
        store.refusesPassword = true

        // Act
        let saved = service.setPassword("new")

        // Assert
        XCTAssertFalse(saved)
        XCTAssertEqual(store.savedPassword, "pw")
        XCTAssertTrue(service.hasPassword)
    }

    func testSavedPasswordIsReported() {
        // Arrange
        let (service, store, _) = make()

        // Act
        let saved = service.setPassword("new")

        // Assert
        XCTAssertTrue(saved)
        XCTAssertEqual(store.savedPassword, "new")
    }

    func testIncompleteDestinationNeverConnects() async throws {
        let (service, _, connector) = make(DestinationConfig())
        XCTAssertFalse(service.isReady)
        do {
            _ = try await service.connect()
            XCTFail("expected failure")
        } catch let e as DestinationError {
            XCTAssertEqual(e, .incomplete)
        }
        let result = await service.test()
        XCTAssertEqual(result, .failed(.destination(.incomplete)))
        XCTAssertTrue(connector.opened.isEmpty)
    }

    func testUpdateSavesOnlyChanges() {
        let (service, store, _) = make()
        XCTAssertFalse(service.update { $0.host = "nas" })
        XCTAssertTrue(service.update { $0.path = "Q" })
        XCTAssertEqual(store.config.path, "Q")
        XCTAssertEqual(service.displayURL, "smb://nas/Share/Q")
        service.setPassword("")
        XCTAssertFalse(service.hasPassword)
    }

    func testTestProvesWriteAccess() async {
        let fs = MemoryFS()
        let (service, _, _) = make(fs: fs)
        let result = await service.test()
        XCTAssertEqual(result, .succeeded)
        XCTAssertTrue(fs.dirs.contains("P/.upload"))
    }

    func testBrowsing() async throws {
        let (service, _, connector) = make()
        connector.shares = [RemoteEntry(name: "b", isDirectory: true, size: 0), RemoteEntry(name: "C$", isDirectory: true, size: 0)]
        guard case .entries(let shares) = await service.listing(for: nil) else { return XCTFail() }
        XCTAssertEqual(shares.map(\.name), ["b"])
        let root = BrowseLocation(share: "b", path: "")
        guard case .missing = await service.listing(for: root.child("Nope")) else { return XCTFail() }
        let made = try await service.createFolder("New/One", in: root)
        XCTAssertEqual(made, BrowseLocation(share: "b", path: "New_One"))
        guard case .entries = await service.listing(for: made) else { return XCTFail() }
        XCTAssertTrue(service.choose(made))
        XCTAssertEqual(service.config.share, "b")
        XCTAssertEqual(service.config.path, "New_One")
        XCTAssertEqual(service.displayURL(for: nil), "smb://nas")
    }

    func testListingSharesNeedsAHost() async {
        let (service, _, _) = make(DestinationConfig())
        guard case .failed(.destination(.invalidHost)) = await service.listing(for: nil) else { return XCTFail() }
    }
}

final class PresentationTests: XCTestCase {
    func testResultBanner() {
        let okResult = CopyResult(copied: 3, alreadyPresent: 1, failed: 0, failures: [], cancelled: false)
        let ok = ResultBanner.from(okResult)
        XCTAssertEqual(ok, .finished(okResult))
        XCTAssertFalse(ok.isError)
        let failure = CopyFailure(fileName: "a", reason: .other("x"))
        let bad = ResultBanner.from(CopyResult(copied: 0, alreadyPresent: 0, failed: 1, failures: [failure], cancelled: false))
        XCTAssertTrue(bad.isError)
        XCTAssertEqual(bad.failures, [failure])
        // A run stopped before copying anything still reports what happened.
        let stopped = CopyResult(copied: 0, alreadyPresent: 0, failed: 0, failures: [], cancelled: true)
        XCTAssertEqual(ResultBanner.from(stopped), .finished(stopped))
        XCTAssertEqual(ResultBanner.from(CopyResult(copied: 0, alreadyPresent: 0, failed: 0, failures: [], cancelled: false)), .nothingNew)
        XCTAssertTrue(ResultBanner.connectFailed(.destination(.timedOut(host: "nas"))).isError)
        XCTAssertFalse(ResultBanner.stopped.isError)
    }

    func testBrowseStack() {
        var c = DestinationConfig()
        XCTAssertEqual(BrowseLocation.stack(for: c), [])
        c.share = "S"; c.path = "/Photos//Phone/"
        XCTAssertEqual(BrowseLocation.stack(for: c), [
            BrowseLocation(share: "S", path: ""), BrowseLocation(share: "S", path: "Photos"),
            BrowseLocation(share: "S", path: "Photos/Phone"),
        ])
        XCTAssertEqual(c.location.name, "Phone")
        c.path = ""
        XCTAssertEqual(c.location.name, "S")
    }

    func testBrowsableEntries() {
        let e = RemoteEntry.browsableEntries([
            RemoteEntry(name: "b.jpg", isDirectory: false, size: 0), RemoteEntry(name: ".upload", isDirectory: true, size: 0),
            RemoteEntry(name: "Z", isDirectory: true, size: 0), RemoteEntry(name: "a.jpg", isDirectory: false, size: 0),
        ])
        XCTAssertEqual(e.map(\.name), ["Z", "a.jpg", "b.jpg"])
        XCTAssertEqual(RemoteEntry.browsableShares([RemoteEntry(name: "b", isDirectory: true, size: 0),
                                                    RemoteEntry(name: "IPC$", isDirectory: true, size: 0),
                                                    RemoteEntry(name: "A", isDirectory: true, size: 0)]).map(\.name), ["A", "b"])
    }
}

// MARK: - Encryption

final class EncryptionRuleTests: XCTestCase {
    func testPasswordSignInIsEncryptedGuestIsNot() {
        var c = DestinationConfig()
        c.smbAuth = .userPassword
        XCTAssertTrue(c.encrypted)
        c.smbAuth = .guest
        XCTAssertFalse(c.encrypted)
    }
}
