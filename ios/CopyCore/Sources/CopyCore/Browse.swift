import Foundation

/// A place in the location browser: a folder inside a share ("" is the share root).
public struct BrowseLocation: Hashable, Sendable {
    public var share: String
    public var path: String

    public init(share: String, path: String) {
        self.share = share
        self.path = path
    }

    public var name: String { path.isEmpty ? share : RemotePath.name(path) }

    public func child(_ name: String) -> BrowseLocation {
        BrowseLocation(share: share, path: RemotePath.join(path, name))
    }

    /// The browser opens at the folder already chosen, with every parent folder on the back
    /// stack. Empty when no share is chosen yet (the browser then shows the server's shares).
    public static func stack(for config: DestinationConfig) -> [BrowseLocation] {
        guard !config.share.isEmpty else { return [] }
        let target = config.location
        var loc = BrowseLocation(share: target.share, path: "")
        var stack = [loc]
        for comp in target.path.split(separator: "/") {
            loc = loc.child(String(comp))
            stack.append(loc)
        }
        return stack
    }
}

/// What the browser shows for one level.
public enum BrowseListing: Sendable {
    case entries([RemoteEntry])
    /// The folder does not exist (yet).
    case missing
    case failed(FailureReason)
}

public extension RemoteEntry {
    /// Shares as the browser lists them: hidden/admin (`$`) shares left out, sorted by name.
    static func browsableShares(_ shares: [RemoteEntry]) -> [RemoteEntry] {
        shares.filter { !$0.name.hasSuffix("$") }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// A folder as the browser lists it: hidden (dot) entries left out, folders first, then by name.
    static func browsableEntries(_ entries: [RemoteEntry]) -> [RemoteEntry] {
        entries.filter { !$0.name.hasPrefix(".") }
            .sorted {
                if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
                return $0.name.localizedStandardCompare($1.name) == .orderedAscending
            }
    }
}
