import CopyCore
import Foundation

/// Controller for the Destination screen: the settings form, testing the connection, and the
/// location browser. The settings, the password and the connections belong to
/// `DestinationService`; this screen is the one place that shows the settings themselves.
@MainActor
final class DestinationController: ObservableObject {
    @Published private(set) var testing = false
    @Published private(set) var testResult: ProbeResult?
    /// The secure store refused the last password typed.
    @Published private(set) var passwordNotSaved = false

    private let destination: DestinationService
    private let background: BackgroundCopy

    /// - Parameters:
    ///   - destination: The destination this screen edits, tests and browses.
    ///   - background: Reschedules automatic copying when the settings change.
    init(destination: DestinationService, background: BackgroundCopy) {
        self.destination = destination
        self.background = background
    }

    var config: DestinationConfig { destination.config }
    var hasPassword: Bool { destination.hasPassword }

    /// Changes the settings and reschedules automatic copying.
    func update(_ change: (inout DestinationConfig) -> Void) {
        if destination.update(change) { background.schedule(for: destination) }
    }

    /// Saves the password as it is typed; an empty one removes it.
    /// - Parameter value: The password typed so far.
    func setPassword(_ value: String) {
        passwordNotSaved = !destination.setPassword(value)
    }

    func testConnection() async {
        testing = true
        testResult = nil
        testResult = await destination.test()
        testing = false
    }

    // MARK: - Location browser

    /// Where the browser opens: the folder already chosen, with its parents on the back stack.
    func browseStack() -> [BrowseLocation] { destination.browseStack() }

    /// The server's shares (`location` nil) or the contents of a folder.
    func listing(for location: BrowseLocation?) async -> BrowseListing {
        await destination.listing(for: location)
    }

    /// Creates a folder in `location` and returns it.
    func createFolder(_ name: String, in location: BrowseLocation) async throws -> BrowseLocation {
        try await destination.createFolder(name, in: location)
    }

    /// Sets the share and the folder together, and closes the browser's connections.
    func choose(_ location: BrowseLocation) {
        if destination.choose(location) { background.schedule(for: destination) }
    }

    func displayURL(_ location: BrowseLocation?) -> String {
        destination.displayURL(for: location)
    }

    func closeBrowser() {
        destination.endBrowsing()
    }
}
