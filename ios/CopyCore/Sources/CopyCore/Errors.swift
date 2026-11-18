import Foundation

/// Why copying an item failed. The views turn it into text.
public enum CopyError: Error, Equatable, Sendable {
    /// The file at the destination has a different size than the local one (`remote` nil: missing).
    case sizeMismatch(local: UInt64, remote: UInt64?)
    case checksumMismatch
    /// A file had the wrong size after it was moved into place.
    case wrongSizeAfterMove(path: String)
    /// The item has no files to copy.
    case nothingToCopy
    case tooManyNameConflicts(name: String)
    /// The photo is no longer on the device.
    case itemMissing
    /// The edited version could not be rendered; `detail` is the system's description, if any.
    case renderFailed(detail: String?)
    /// Another copy is already in progress.
    case alreadyRunning
}

/// Why a destination could not be used. The views turn it into text.
public enum DestinationError: Error, Equatable, Sendable {
    /// Server, sign-in or share is missing from the configuration.
    case incomplete
    case invalidHost
    case signInRefused(host: String, share: String?)
    case connectionRefused(host: String)
    case accessDenied(host: String, share: String?)
    case shareMissing(host: String, share: String)
    case notADiskShare(host: String, share: String)
    case timedOut(host: String)
    case unreachable(host: String)
    /// Anything else; `detail` is the system's own description, when it has one.
    case other(host: String, code: Int, detail: String?)
}

/// Why something failed, as data. The views turn it into (translated) text.
public enum FailureReason: Sendable, Equatable {
    case copy(CopyError)
    case destination(DestinationError)
    /// No file or folder at the path.
    case notFound
    /// Any other error, with the system's own description of it.
    case other(String)

    public init(_ error: Error) {
        switch error {
        case let e as CopyError: self = .copy(e)
        case let e as DestinationError: self = .destination(e)
        case is RemoteError: self = .notFound
        default: self = .other(error.localizedDescription)
        }
    }
}

/// An item that could not be copied, and why.
public struct CopyFailure: Sendable, Equatable {
    public var fileName: String
    public var reason: FailureReason

    public init(fileName: String, reason: FailureReason) {
        self.fileName = fileName
        self.reason = reason
    }
}
