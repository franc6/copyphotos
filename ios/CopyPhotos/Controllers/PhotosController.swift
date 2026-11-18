import CopyCore
import Photos

/// How deleting copied photos from the device ended. (Android also has "moved to the trash";
/// on iOS the system dialog moves them to Recently Deleted, which Photos calls deleting.)
enum DeleteOutcome: Equatable {
    case deleted(Int)
}

/// "Delete copied photos from device", step by step.
struct DeleteState: Equatable {
    var working = false
    var error: FailureReason?
    var done: DeleteOutcome?
}

/// Controller for the Photos screen: the photo list and which photos are copied, running
/// copies, copy history, and deleting copied photos from the device.
@MainActor
final class PhotosController: ObservableObject {
    enum Filter: CaseIterable, Identifiable {
        case all, notCopied, copied
        var id: Self { self }
    }

    private let service: CopyService
    private let jobs: CopyJobs
    private let destination: DestinationService
    private let library: PhotoLibrary

    @Published private(set) var access: LibraryAccess
    @Published private(set) var assets: [PHAsset] = []
    @Published private(set) var copiedIDs: Set<String> = []
    @Published var filter: Filter = .all
    @Published private(set) var delete = DeleteState()

    private var libraryChanges: Task<Void, Never>?

    /// - Parameters:
    ///   - service: Which photos are copied, and the copy history.
    ///   - jobs: Runs copies and keeps the result banner.
    ///   - destination: Whether there is somewhere to copy to.
    ///   - library: The photos to show, and deleting them.
    init(service: CopyService, jobs: CopyJobs, destination: DestinationService, library: PhotoLibrary) {
        self.service = service
        self.jobs = jobs
        self.destination = destination
        self.library = library
        access = library.access()
        startObservingLibrary()
    }

    // MARK: - State for the view

    /// Where photos are copied to, or nil when no destination is set up.
    var destinationURL: String? { destination.isReady ? destination.displayURL : nil }
    var runState: CopyRunState { jobs.runState }
    var banner: ResultBanner? { jobs.banner }
    var isRunning: Bool { runState.isActive }
    /// A copy can start: the destination is set up and no copy is running.
    var canCopy: Bool { destination.isReady && !isRunning }

    func isCopied(_ asset: PHAsset) -> Bool { copiedIDs.contains(asset.localIdentifier) }

    var visible: [PHAsset] {
        switch filter {
        case .all: return assets
        case .notCopied: return assets.filter { !isCopied($0) }
        case .copied: return assets.filter(isCopied)
        }
    }

    var copiedCount: Int { assets.lazy.filter(isCopied).count }
    var uncopiedCount: Int { assets.count - copiedCount }

    // MARK: - Library

    func requestAccess() async {
        access = await library.requestAccess()
        startObservingLibrary()
    }

    /// Reloads now, and again (copying new photos if automatic copying is on) whenever the library changes.
    private func startObservingLibrary() {
        guard access.canRead else { return }
        if libraryChanges == nil {
            libraryChanges = Task { [weak self] in
                for await _ in library.changes() {
                    guard let self else { return }
                    self.reload()
                    if self.destination.config.autoCopyEnabled { self.copyNew(automatic: true) }
                }
            }
        }
        reload()
    }

    func reload() {
        Task {
            let fetched = library.fetchAssets()
            let copied = await service.copiedIDsOnDevice()
            assets = fetched
            copiedIDs = copied
        }
    }

    /// Called when the app becomes active: refresh, then copy new photos if automatic copying is on.
    func onAppActive() {
        access = library.access()
        guard access.canRead else { return }
        startObservingLibrary()
        if destination.config.autoCopyEnabled { copyNew(automatic: true) }
    }

    // MARK: - Copy

    /// Copies photos that have never been copied (never re-copies anything).
    func copyNew(automatic: Bool = false) {
        guard canCopy else { return }
        Task {
            await jobs.copyNew(automatic: automatic)
            copiedIDs = await service.copiedIDsOnDevice()
        }
    }

    /// Copies the given photos even if they were copied before ("copy again").
    func copy(ids: [String]) {
        guard canCopy else { return }
        Task {
            await jobs.copy(ids: ids)
            copiedIDs = await service.copiedIDsOnDevice()
        }
    }

    /// Copies every photo again, ignoring the copy history.
    func copyAllAgain() {
        copy(ids: assets.map(\.localIdentifier))
    }

    func cancel() {
        jobs.cancel()
    }

    func dismissBanner() {
        jobs.dismissBanner()
    }

    // MARK: - History

    /// Forgets all copy history, so automatic copying will copy everything again.
    func forgetHistory() async {
        try? await service.forgetHistory()
        copiedIDs = []
    }

    // MARK: - Delete copied photos from device

    /// Deletes every copied item that is still on the device. The only criterion is that it was
    /// copied; the destination is not consulted, since copies are independent of the device.
    /// iOS asks the user to confirm.
    func startDelete() async {
        delete = DeleteState(working: true)
        let ids = Array(await service.copiedIDsOnDevice())
        do {
            if !ids.isEmpty {
                try await library.delete(ids: ids)
                // They are no longer on the device, so they leave the ledger.
                try? await service.recordDeleted(ids)
                reload()
            }
            delete = DeleteState(done: .deleted(ids.count))
        } catch {
            delete = DeleteState(error: FailureReason(error))
        }
    }

    func resetDelete() {
        delete = DeleteState()
    }
}
