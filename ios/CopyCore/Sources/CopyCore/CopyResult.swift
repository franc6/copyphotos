import Foundation

/// Whether a copy is running, as the Photos screen shows it.
public enum CopyRunState: Sendable {
    case idle
    /// Queued, waiting for its conditions (e.g. a network connection). Android only.
    case waiting
    /// Running; progress is nil until the first report arrives.
    case running(CopyProgress?)

    public var isActive: Bool {
        if case .idle = self { return false }
        return true
    }

    public var progress: CopyProgress? {
        if case .running(let p) = self { return p }
        return nil
    }
}

/// The outcome of a copy run, reduced to what the result banner shows.
public struct CopyResult: Sendable, Equatable {
    public var copied: Int
    public var alreadyPresent: Int
    /// How many items failed.
    public var failed: Int
    /// The failed items (may be fewer than `failed` if shortened).
    public var failures: [CopyFailure]
    public var cancelled: Bool

    public init(copied: Int, alreadyPresent: Int, failed: Int, failures: [CopyFailure], cancelled: Bool) {
        self.copied = copied
        self.alreadyPresent = alreadyPresent
        self.failed = failed
        self.failures = failures
        self.cancelled = cancelled
    }

    public init(_ summary: CopySummary) {
        self.init(copied: summary.copied.count, alreadyPresent: summary.alreadyPresent.count,
                  failed: summary.failures.count, failures: summary.failures, cancelled: summary.cancelled)
    }
}

/// Which banner the Photos screen shows after a copy (same choice on iOS and Android).
public enum ResultBanner: Sendable, Equatable {
    /// The copy ran (possibly stopped part-way, possibly with failures).
    case finished(CopyResult)
    /// There was nothing to copy.
    case nothingNew
    /// Stopped before it reported a result.
    case stopped
    /// The copy could not start (e.g. could not connect).
    case connectFailed(FailureReason)

    public static func from(_ r: CopyResult) -> ResultBanner {
        r.copied == 0 && r.alreadyPresent == 0 && r.failed == 0 && !r.cancelled ? .nothingNew : .finished(r)
    }

    public var isError: Bool {
        switch self {
        case .finished(let r): return r.failed > 0
        case .connectFailed: return true
        case .nothingNew, .stopped: return false
        }
    }

    public var failures: [CopyFailure] {
        if case .finished(let r) = self { return r.failures }
        return []
    }
}
