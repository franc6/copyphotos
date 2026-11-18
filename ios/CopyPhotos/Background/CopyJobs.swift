import CopyCore
import Foundation

/// The copies started while the app is open (by the user or by automatic copying): one at a
/// time, with their progress and the banner of the last one. A copy keeps running when the
/// user leaves the screen, and for a while when they leave the app.
@MainActor
@Observable
final class CopyJobs {
    private(set) var runState: CopyRunState = .idle
    private(set) var banner: ResultBanner?

    @ObservationIgnored private let service: CopyService
    @ObservationIgnored private let background: BackgroundCopy
    @ObservationIgnored private var task: Task<Void, Never>?

    /// - Parameters:
    ///   - service: Runs the copies.
    ///   - background: Keeps a copy running for a while when the user leaves the app.
    init(service: CopyService, background: BackgroundCopy) {
        self.service = service
        self.background = background
    }

    /// Copies photos that have never been copied (never re-copies anything). Returns when the
    /// copy has finished. A copy the user asked for reports when there is nothing new.
    func copyNew(automatic: Bool) async {
        guard !runState.isActive else { return }
        let items = (try? await service.uncopiedItems()) ?? []
        if items.isEmpty {
            if !automatic { banner = .nothingNew }
            return
        }
        await run(items)
    }

    /// Copies the given photos even if they were copied before ("copy again"). Returns when the
    /// copy has finished.
    func copy(ids: [String]) async {
        guard !runState.isActive else { return }
        await run((try? await service.items(ids: ids)) ?? [])
    }

    func cancel() {
        task?.cancel()
    }

    func dismissBanner() {
        banner = nil
    }

    private func run(_ items: [CopyItem]) async {
        guard !items.isEmpty, !runState.isActive else { return }
        runState = .running(CopyProgress(completed: 0, failed: 0, total: items.count, currentName: nil, currentBytes: 0))
        banner = nil
        let task = Task {
            // Keeps copying for a while if the user leaves the app mid-copy.
            await background.whileRunning(onExpiry: { self.cancel() }) {
                do {
                    let summary = try await service.run(items) { p in
                        Task { @MainActor in if self.runState.isActive { self.runState = .running(p) } }
                    }
                    banner = .from(CopyResult(summary))
                } catch {
                    banner = .connectFailed(FailureReason(error))
                }
            }
            runState = .idle
        }
        self.task = task
        await task.value
    }
}
