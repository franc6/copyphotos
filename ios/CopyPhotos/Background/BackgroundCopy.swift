import BackgroundTasks
import CopyCore
import UIKit

/// Copying while the app is not in front: automatic copies of new photos in a
/// BGProcessingTask (iOS decides when it runs; typically when idle on Wi-Fi), and extra time
/// for a copy the user started when they leave the app.
struct BackgroundCopy {
    private static let taskID = "com.copyphotos.autocopy"

    /// Whether automatic copies may read the library.
    let library: PhotoLibrary

    /// Registers the automatic-copy task with iOS. Call once, at launch.
    /// - Parameters:
    ///   - service: Runs the automatic copies.
    ///   - destination: Whether automatic copying is on, and how to reschedule.
    func register(service: CopyService, destination: DestinationService) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskID, using: nil) { task in
            handle(task as! BGProcessingTask, service: service, destination: destination)
        }
    }

    /// Schedules the next run for the destination's settings, or cancels it if automatic copying is off.
    /// - Parameter destination: Whether automatic copying is on, and whether it needs power.
    @MainActor
    func schedule(for destination: DestinationService) {
        let config = destination.config
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskID)
        guard config.autoCopyEnabled else { return }
        let request = BGProcessingTaskRequest(identifier: Self.taskID)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = config.autoCopyRequiresPower
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Runs `work`, asking iOS for extra time if the user leaves the app meanwhile.
    /// `onExpiry` is called if that time runs out (it should stop the work). The extra time is
    /// handed back right there too: iOS ends the app if it isn't, and stopping an SMB transfer
    /// can take longer than the few seconds iOS allows.
    /// - Parameters:
    ///   - onExpiry: Stops the work when the extra time runs out.
    ///   - work: The work to keep running.
    @MainActor
    func whileRunning(onExpiry: @escaping @MainActor @Sendable () -> Void, _ work: () async -> Void) async {
        var id = UIBackgroundTaskIdentifier.invalid
        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
        id = UIApplication.shared.beginBackgroundTask(withName: "CopyPhotos") {
            MainActor.assumeIsolated {
                onExpiry()
                end()
            }
        }
        await work()
        end()
    }

    /// Runs one automatic copy for iOS, then schedules the next.
    /// - Parameters:
    ///   - task: The task iOS started.
    ///   - service: Runs the copy.
    ///   - destination: Whether automatic copying is still on.
    private func handle(_ task: BGProcessingTask, service: CopyService, destination: DestinationService) {
        let work = Task { @MainActor in
            guard destination.config.autoCopyEnabled, library.access() == .full else { return true }
            do {
                let items = try await service.uncopiedItems()
                guard !items.isEmpty else { return true }
                let summary = try await service.run(items) { _ in }
                return !summary.cancelled
            } catch {
                return false
            }
        }
        task.expirationHandler = { work.cancel() }
        Task { @MainActor in
            let ok = await work.value
            schedule(for: destination)
            task.setTaskCompleted(success: ok)
        }
    }
}
