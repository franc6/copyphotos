import CopyCore
import Foundation

// The one place that turns typed results, errors and enums into text. Every string here is
// looked up in Localizable.xcstrings.

/// Parts of one line, joined the way this language separates them ("a · b").
func joined(_ parts: [String]) -> String {
    parts.joined(separator: String(localized: "ListSeparator", defaultValue: " · ",
                                   comment: "Between the parts of a one-line summary, e.g. “3 copied · 1 failed”"))
}

/// A place name from its parts ("Cupertino, California"), or nil when there are none.
func placeName(_ parts: [String]) -> String? {
    parts.isEmpty ? nil : parts.joined(separator: String(localized: "PlaceSeparator", defaultValue: ", ",
                                                                comment: "Between the parts of a place name, e.g. “Cupertino, California”"))
}

extension ResultBanner {
    var title: String {
        switch self {
        case .finished(let r):
            if r.cancelled { return String(localized: "Copy stopped") }
            return r.failed > 0 ? String(localized: "Copy finished with errors") : String(localized: "Copy complete")
        case .nothingNew: return String(localized: "Nothing new to copy")
        case .stopped: return String(localized: "Copy stopped")
        case .connectFailed: return String(localized: "Could not connect")
        }
    }

    var detail: String {
        switch self {
        case .finished(let r):
            var parts = [String(localized: "\(r.copied) copied and verified")]
            if r.alreadyPresent > 0 { parts.append(String(localized: "\(r.alreadyPresent) already on destination")) }
            if r.failed > 0 { parts.append(String(localized: "\(r.failed) failed")) }
            return joined(parts)
        case .nothingNew: return String(localized: "Every photo on this device has already been copied.")
        case .stopped: return String(localized: "Copied photos so far are recorded.")
        case .connectFailed(let reason): return reason.text
        }
    }
}

extension CopyFailure {
    var text: String { String(localized: "\(fileName): \(reason.text)", comment: "A file that failed to copy, and why") }
}

extension FailureReason {
    var text: String {
        switch self {
        case .copy(let e): return e.text
        case .destination(let e): return e.text
        case .notFound: return String(localized: "Not found on the server.")
        case .other(let message): return message
        }
    }
}

extension CopyError {
    /// The error as a sentence.
    var text: String {
        switch self {
        case .sizeMismatch, .checksumMismatch, .wrongSizeAfterMove: return verificationText
        case .nothingToCopy, .tooManyNameConflicts, .itemMissing, .renderFailed, .alreadyRunning: return copyText
        }
    }

    /// The text of an error found while verifying a written file.
    private var verificationText: String {
        switch self {
        case .sizeMismatch(let local, let remote?):
            return String(localized: "Verification failed: size mismatch (local \(local), remote \(remote))")
        case .wrongSizeAfterMove(let path):
            return String(localized: "Verification failed: \(path) has the wrong size after moving it into place")
        case .sizeMismatch(_, nil): return String(localized: "Verification failed: the file is missing at the destination")
        case .checksumMismatch: return String(localized: "Verification failed: checksum mismatch")
        default: preconditionFailure("not a verification error: \(self)")
        }
    }

    /// The text of an error that kept an item from being exported or written.
    private var copyText: String {
        switch self {
        case .nothingToCopy: return String(localized: "The photo has no exportable data.")
        case .tooManyNameConflicts(let name): return String(localized: "Too many files named like \(name) at the destination")
        case .itemMissing: return String(localized: "The photo is no longer in the library.")
        case .renderFailed(let detail?): return String(localized: "Could not export the edited version: \(detail)")
        case .renderFailed(nil): return String(localized: "Could not export the edited version.")
        case .alreadyRunning: return String(localized: "Another copy is already in progress.")
        default: preconditionFailure("not a copy error: \(self)")
        }
    }
}

extension DestinationError {
    /// The error as a sentence.
    var text: String {
        switch self {
        case .signInRefused, .accessDenied, .shareMissing, .notADiskShare: return accessText
        case .incomplete, .invalidHost, .connectionRefused, .timedOut, .unreachable, .other: return connectionText
        }
    }

    /// The text of an error about signing in or using a share.
    private var accessText: String {
        switch self {
        case .signInRefused(let host, let share?):
            return String(localized: "\(host) refused the sign-in or the share “\(share)”. Check the user name, password and share name.")
        case .signInRefused(let host, nil):
            return String(localized: "\(host) refused the sign-in. Check the user name and password.")
        case .accessDenied(let host, let share?):
            return String(localized: "Access denied by \(host) or the share “\(share)”. Check that this user may use it.")
        case .accessDenied(let host, nil):
            return String(localized: "Access denied by \(host). Check that this user may use it.")
        case .shareMissing(let host, let share): return String(localized: "The share “\(share)” does not exist on \(host).")
        case .notADiskShare(let host, let share): return String(localized: "“\(share)” on \(host) is not a disk share.")
        default: preconditionFailure("not an access error: \(self)")
        }
    }

    /// The text of an error about reaching the server.
    private var connectionText: String {
        switch self {
        case .incomplete: return String(localized: "The destination is not fully configured.")
        case .invalidHost: return String(localized: "The server address is not valid.")
        case .connectionRefused(let host):
            return String(localized: "\(host) refused the connection or the sign-in. Check that SMB is enabled on port 445 and that the user name and password are correct.")
        case .timedOut(let host): return String(localized: "\(host) did not respond in time.")
        case .unreachable(let host): return String(localized: "Cannot reach \(host) from this device’s network.")
        // The server's own text, when it gave one, says more than the code.
        case .other(let host, let code, let detail):
            return detail ?? String(localized: "Could not connect to \(host) (error \(code)).")
        default: preconditionFailure("not a connection error: \(self)")
        }
    }
}

extension ProbeResult {
    func text(for config: DestinationConfig) -> String {
        switch self {
        case .succeeded: return String(localized: "Connected and verified write access to \(config.displayURL)")
        case .failed(let reason): return reason.text
        }
    }
}

extension DeleteOutcome {
    var text: String {
        switch self {
        case .deleted(let n): return String(localized: "Deleted \(n) items from this device.")
        }
    }
}

extension VerifyMode {
    var label: String {
        switch self {
        case .readBackChecksum: return String(localized: "Read back & compare SHA-256")
        case .sizeOnly: return String(localized: "Compare file size")
        }
    }
}

extension SMBAuthMethod {
    var label: String {
        switch self {
        case .userPassword: return String(localized: "Username & password (NTLMv2)")
        case .guest: return String(localized: "Guest / anonymous (not encrypted)")
        }
    }
}

extension PhotosController.Filter {
    var label: String {
        switch self {
        case .all: return String(localized: "All")
        case .notCopied: return String(localized: "Not copied")
        case .copied: return String(localized: "Copied")
        }
    }
}

extension AssetKind {
    /// The kind's name, as Photos shows it.
    var label: String {
        switch self {
        case .livePhoto, .portrait, .panorama, .hdr, .screenshot, .burst: return photoLabel
        case .sloMo, .timeLapse, .cinematic, .spatial, .screenRecording: return videoLabel
        }
    }

    /// The name of a kind of photo.
    private var photoLabel: String {
        switch self {
        case .livePhoto: return String(localized: "Live Photo")
        case .portrait: return String(localized: "Portrait")
        case .panorama: return String(localized: "Panorama")
        case .hdr: return String(localized: "HDR")
        case .screenshot: return String(localized: "Screenshot")
        case .burst: return String(localized: "Burst")
        default: preconditionFailure("not a photo kind: \(self)")
        }
    }

    /// The name of a kind of video.
    private var videoLabel: String {
        switch self {
        case .sloMo: return String(localized: "Slo-mo")
        case .timeLapse: return String(localized: "Time-lapse")
        case .cinematic: return String(localized: "Cinematic")
        case .spatial: return String(localized: "Spatial")
        case .screenRecording: return String(localized: "Screen Recording")
        default: preconditionFailure("not a video kind: \(self)")
        }
    }
}

extension AssetFileRole {
    func label(isVideo: Bool) -> String {
        switch self {
        case .original: return String(localized: "Original")
        case .alternateOriginal: return String(localized: "RAW original")
        case .livePhotoVideo: return String(localized: "Live Photo video")
        case .edited: return isVideo ? String(localized: "Edited video") : String(localized: "Edited photo")
        case .editedLivePhotoVideo: return String(localized: "Edited Live Photo video")
        case .adjustments: return String(localized: "Edits (.AAE)")
        }
    }
}
