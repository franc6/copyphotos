import CopyCore
import Foundation

/// Composition root: one of each shared object, made once at launch and passed to the
/// controllers and the background task. The destination's settings are reached only through
/// `DestinationService`, the password is read only by the connector, and the ledger is reached
/// only through `CopyService`.
@MainActor
final class AppEnvironment {
    let destination: DestinationService
    let service: CopyService
    let jobs: CopyJobs
    let library: PhotoLibrary
    let background: BackgroundCopy
    let photoInfo: PhotoInfoLoader

    /// Makes every shared object.
    init() {
        let library = PhotoLibrary()
        // Nothing is copying yet, so anything here was left by a copy the system killed.
        library.removeLeftoverExports()
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let keychain = Keychain(service: "CopyPhotos.share", account: "smb")
        destination = DestinationService(store: DestinationPreferences(defaults: .standard, keychain: keychain),
                                         connector: SMBConnector(keychain: keychain))
        service = CopyService(library: library, source: library,
                              ledger: CopyLedger(fileURL: dir.appendingPathComponent("copy-ledger.json")),
                              destination: destination)
        self.library = library
        background = BackgroundCopy(library: library)
        photoInfo = PhotoInfoLoader()
        jobs = CopyJobs(service: service, background: background)
    }
}
