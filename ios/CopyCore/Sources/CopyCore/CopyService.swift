import Foundation

/// How much of the device's photo library the app may see.
public enum LibraryAccess: Sendable, Equatable {
    /// Not asked yet.
    case notDetermined
    case none
    /// Only the photos the user selected.
    case limited
    case full

    public var canRead: Bool { self == .limited || self == .full }
}

/// The photos and videos on the device.
public protocol MediaLibrary: Sendable {
    /// IDs of every photo and video on the device.
    func allIDs() async throws -> Set<String>
    /// These items, if they are still on the device, in any order.
    func copyItems(ids: [String]) async throws -> [CopyItem]
}

/// The copy use cases, shared by the screens and the background task: which photos to copy,
/// running a copy, and keeping the ledger in step with the device.
public final class CopyService: Sendable {
    private let library: MediaLibrary
    private let source: CopySource
    private let ledger: any Ledger
    private let destination: DestinationService
    private let gate = RunGate()

    public init(library: MediaLibrary, source: CopySource, ledger: any Ledger, destination: DestinationService) {
        self.library = library
        self.source = source
        self.ledger = ledger
        self.destination = destination
    }

    /// IDs of copied photos that are still on the device. Photos deleted from the device are
    /// forgotten first, so the ledger only ever describes what is still there. If the library
    /// can't be read, nothing is forgotten.
    public func copiedIDsOnDevice() async -> Set<String> {
        if let all = try? await library.allIDs() {
            _ = try? await ledger.prune(keeping: all)
        }
        return await ledger.copiedIDs()
    }

    /// Photos that have never been copied, oldest first so an interrupted run makes steady
    /// progress. Every automatic copy uses this, so nothing is ever copied again automatically.
    public func uncopiedItems() async throws -> [CopyItem] {
        let all = try await library.allIDs()
        _ = try? await ledger.prune(keeping: all)
        let uncopied = all.subtracting(await ledger.copiedIDs())
        guard !uncopied.isEmpty else { return [] }
        return Self.oldestFirst(try await library.copyItems(ids: Array(uncopied)))
    }

    /// These photos, oldest first, whether or not they were copied before ("copy again").
    public func items(ids: [String]) async throws -> [CopyItem] {
        guard !ids.isEmpty else { return [] }
        return Self.oldestFirst(try await library.copyItems(ids: ids))
    }

    public func record(for id: String) async -> CopyRecord? {
        await ledger.record(for: id)
    }

    /// Copies `items` to the destination, verifying each file. Only one copy runs at a time.
    public func run(_ items: [CopyItem], progress: @escaping @Sendable (CopyProgress) -> Void) async throws -> CopySummary {
        guard await gate.enter() else { throw CopyError.alreadyRunning }
        defer { Task { await gate.leave() } }
        let options = await destination.copyOptions
        let fs = try await destination.connect()
        let engine = CopyEngine(fs: fs, source: source, ledger: ledger, options: options)
        let summary = await engine.copy(items, progress: progress)
        await fs.close()
        return summary
    }

    /// Forgets all copy history, so automatic copying will copy everything again. Nothing is deleted.
    public func forgetHistory() async throws {
        try await ledger.clear()
    }

    /// These photos left the device, so they leave the ledger. The destination is never touched.
    public func recordDeleted(_ ids: [String]) async throws {
        try await ledger.remove(ids)
    }

    static func oldestFirst(_ items: [CopyItem]) -> [CopyItem] {
        items.sorted { ($0.creationDate ?? .distantPast) < ($1.creationDate ?? .distantPast) }
    }
}

/// Ensures only one copy runs at a time (foreground and background share it).
private actor RunGate {
    private var busy = false
    func enter() -> Bool {
        if busy { return false }
        busy = true
        return true
    }
    func leave() { busy = false }
}

