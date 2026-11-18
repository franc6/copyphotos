import Foundation

/// The ledger, stored as a JSON snapshot (`fileURL`) plus a log of the records added since it,
/// one JSON line each. Adding a record appends one line rather than rewriting every record, so
/// a large first copy doesn't rewrite a file of many megabytes after every photo. Removing records
/// writes a new snapshot under the next generation and starts a new log; a log from any other
/// generation (left by a crash in between) is ignored and deleted.
public actor CopyLedger: Ledger {
    private let fileURL: URL
    private var records: [String: CopyRecord] = [:]
    private var generation = 0

    private struct Snapshot: Codable {
        var generation: Int
        var records: [String: CopyRecord]
    }

    public init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let snapshot = try? Self.decoder.decode(Snapshot.self, from: data) {
            records = snapshot.records
            generation = snapshot.generation
        }
        let log = Self.logURL(fileURL, generation)
        if let text = try? String(contentsOf: log, encoding: .utf8) {
            // A line cut short by a crash doesn't decode and is skipped.
            for line in text.split(separator: "\n") {
                if let r = try? Self.decoder.decode(CopyRecord.self, from: Data(line.utf8)) { records[r.assetID] = r }
            }
        }
        Self.removeLogs(of: fileURL, except: log)
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    /// "copy-ledger.json" -> "copy-ledger.json.3.log"
    private static func logURL(_ fileURL: URL, _ generation: Int) -> URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("\(fileURL.lastPathComponent).\(generation).log")
    }

    private static func removeLogs(of fileURL: URL, except keep: URL) {
        let dir = fileURL.deletingLastPathComponent()
        let prefix = fileURL.lastPathComponent + "."
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        for name in names where name.hasPrefix(prefix) && name.hasSuffix(".log") && name != keep.lastPathComponent {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
    }

    public func record(for assetID: String) -> CopyRecord? { records[assetID] }

    public func copiedIDs() -> Set<String> { Set(records.keys) }

    public func add(_ record: CopyRecord) throws {
        records[record.assetID] = record
        try append(record)
    }

    public func remove(_ ids: [String]) throws {
        var changed = false
        for id in ids where records.removeValue(forKey: id) != nil { changed = true }
        if changed { try saveSnapshot() }
    }

    @discardableResult
    public func prune(keeping existingIDs: Set<String>) throws -> Int {
        let stale = records.keys.filter { !existingIDs.contains($0) }
        guard !stale.isEmpty else { return 0 }
        stale.forEach { records.removeValue(forKey: $0) }
        try saveSnapshot()
        return stale.count
    }

    public func clear() throws {
        guard !records.isEmpty else { return }
        records = [:]
        try saveSnapshot()
    }

    /// Appends one line to the current log. The line starts with a newline, so it never joins a
    /// line that a crash cut short.
    private func append(_ record: CopyRecord) throws {
        let url = Self.logURL(fileURL, generation)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("\n".utf8) + Self.encoder.encode(record))
    }

    /// Writes every record as the next generation's snapshot, then drops the old log.
    private func saveSnapshot() throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let old = Self.logURL(fileURL, generation)
        let data = try Self.encoder.encode(Snapshot(generation: generation + 1, records: records))
        try data.write(to: fileURL, options: [.atomic])
        generation += 1
        try? FileManager.default.removeItem(at: old)
    }
}
