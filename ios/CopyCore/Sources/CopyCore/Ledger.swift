import Foundation

/// One file written to the destination.
public struct CopiedFile: Codable, Sendable, Equatable {
    /// Path relative to the share/export root.
    public var remotePath: String
    public var size: UInt64
    public var sha256Hex: String

    public init(remotePath: String, size: UInt64, sha256Hex: String) {
        self.remotePath = remotePath
        self.size = size
        self.sha256Hex = sha256Hex
    }
}

/// One verified copy of a device photo or video (all of its files).
public struct CopyRecord: Codable, Sendable, Equatable {
    /// Device-local identifier of the asset (PHAsset.localIdentifier).
    public var assetID: String
    /// Human-readable destination, e.g. "smb://nas/Photos".
    public var destination: String
    public var files: [CopiedFile]
    public var copiedAt: Date

    public init(assetID: String, destination: String, files: [CopiedFile], copiedAt: Date) {
        self.assetID = assetID
        self.destination = destination
        self.files = files
        self.copiedAt = copiedAt
    }
}

/// Which device photos have been copied and verified. The ledger only ever describes photos
/// that are still on the device, and nothing here ever deletes anything on the destination.
public protocol Ledger: Sendable {
    func copiedIDs() async -> Set<String>
    func record(for assetID: String) async -> CopyRecord?
    func add(_ record: CopyRecord) async throws
    /// Forget that these photos were copied (e.g. after they were deleted from the device).
    func remove(_ ids: [String]) async throws
    @discardableResult
    func prune(keeping existingIDs: Set<String>) async throws -> Int
    /// Forgets every record.
    func clear() async throws
}
