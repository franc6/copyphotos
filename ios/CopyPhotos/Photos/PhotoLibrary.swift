import AVFoundation
import CopyCore
import Foundation
import Photos
import UIKit
import UniformTypeIdentifiers

/// The device's photo library (PhotoKit): what the screens show, and the copy service's
/// `MediaLibrary` and `CopySource`. Stateless; the app makes one in `AppEnvironment`.
struct PhotoLibrary: MediaLibrary, CopySource {
    /// How much of the library the app may read.
    /// - Returns: The current access level.
    func access() -> LibraryAccess {
        Self.access(PHPhotoLibrary.authorizationStatus(for: .readWrite))
    }

    /// Asks the user for access to the library, if they haven't been asked yet.
    /// - Returns: The access level after asking.
    func requestAccess() async -> LibraryAccess {
        Self.access(await PHPhotoLibrary.requestAuthorization(for: .readWrite))
    }

    private static func access(_ status: PHAuthorizationStatus) -> LibraryAccess {
        switch status {
        case .authorized: return .full
        case .limited: return .limited
        case .notDetermined: return .notDetermined
        default: return .none
        }
    }

    /// Every photo of a burst, not just the one Photos shows, and the Hidden album too.
    private static func allAssetsOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.includeAllBurstAssets = true
        options.includeHiddenAssets = true
        return options
    }

    private static func photosAndVideosOptions(sorted: Bool = true) -> PHFetchOptions {
        let options = allAssetsOptions()
        if sorted { options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)] }
        options.predicate = NSPredicate(format: "mediaType == %d || mediaType == %d",
                                        PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        return options
    }

    private static func array(_ result: PHFetchResult<PHAsset>) -> [PHAsset] {
        result.objects(at: IndexSet(integersIn: 0..<result.count))
    }

    /// All photos and videos, newest first.
    /// - Returns: The assets.
    func fetchAssets() -> [PHAsset] {
        Self.array(PHAsset.fetchAssets(with: Self.photosAndVideosOptions()))
    }

    /// IDs of every photo and video on the device.
    static func allAssetIDs() -> Set<String> {
        let result = PHAsset.fetchAssets(with: photosAndVideosOptions(sorted: false))
        var ids = Set<String>()
        result.enumerateObjects { asset, _, _ in ids.insert(asset.localIdentifier) }
        return ids
    }

    static func assets(withIDs ids: [String]) -> [PHAsset] {
        guard !ids.isEmpty else { return [] }
        return array(PHAsset.fetchAssets(withLocalIdentifiers: ids, options: allAssetsOptions()))
    }

    /// Fires whenever the library changes, once a burst of changes has settled (e.g. while
    /// photos are imported), until the caller stops listening.
    /// - Parameter settle: How long changes must stop arriving before the stream fires.
    /// - Returns: The stream of settled changes.
    func changes(settle: Duration = .seconds(3)) -> AsyncStream<Void> {
        AsyncStream { continuation in
            let observer = LibraryChangeObserver(settle: settle) { continuation.yield() }
            PHPhotoLibrary.shared().register(observer)
            continuation.onTermination = { _ in
                PHPhotoLibrary.shared().unregisterChangeObserver(observer)
                observer.cancel()
            }
        }
    }

    /// A preview at most `side` points square: a quick low-quality image first, then the final one.
    /// Downloads from iCloud if needed; cancelled when the caller stops listening.
    static func images(for asset: PHAsset, side: CGFloat, contentMode: PHImageContentMode) -> AsyncStream<UIImage> {
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.isNetworkAccessAllowed = true
        options.resizeMode = .fast
        let size = CGSize(width: side, height: side)
        return AsyncStream { continuation in
            let id = PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: contentMode,
                                                           options: options) { img, info in
                if let img { continuation.yield(img) }
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if !degraded { continuation.finish() }
            }
            continuation.onTermination = { _ in PHImageManager.default().cancelImageRequest(id) }
        }
    }

    /// Every stored file that makes up the asset:
    /// - photo: original, RAW/alternate original, Live Photo video,
    ///   edited photo, edited Live Photo video, edit recipe (.AAE);
    /// - video: original, edited video, edit recipe.
    static func files(for asset: PHAsset) -> [(role: AssetFileRole, resource: PHAssetResource)] {
        let rs = PHAssetResource.assetResources(for: asset)
        func first(_ t: PHAssetResourceType) -> PHAssetResource? { rs.first { $0.type == t } }
        var out: [(role: AssetFileRole, resource: PHAssetResource)] = []
        func add(_ role: AssetFileRole, _ r: PHAssetResource?) {
            guard let r else { return }
            // Something must be the original even if the library only kept a rendered version.
            let hasOriginal = out.contains { $0.role == .original }
            out.append((hasOriginal || role == .adjustments ? role : .original, r))
        }
        if asset.mediaType == .video {
            add(.original, first(.video))
            add(.edited, first(.fullSizeVideo))
        } else {
            add(.original, first(.photo))
            add(.alternateOriginal, first(.alternatePhoto))
            add(.livePhotoVideo, first(.pairedVideo))
            add(.edited, first(.fullSizePhoto))
            add(.editedLivePhotoVideo, first(.fullSizePairedVideo))
        }
        add(.adjustments, first(.adjustmentData))
        if out.isEmpty, let any = rs.first { out.append((.original, any)) }
        return out
    }

    static func baseFileName(for asset: PHAsset) -> String {
        files(for: asset).first { $0.role == .original }?.resource.originalFilename
            ?? "\(asset.localIdentifier.prefix(8)).\(asset.mediaType == .video ? "MOV" : "JPG")"
    }


    /// Deletes assets from the device, after iOS's own confirmation, into "Recently Deleted".
    /// - Parameter ids: The local identifiers of the assets to delete.
    func delete(ids: [String]) async throws {
        let assets = Self.assets(withIDs: ids)
        guard !assets.isEmpty else { return }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.deleteAssets(assets as NSArray)
        }
    }
}

// MARK: - MediaLibrary and CopySource

extension PhotoLibrary {
    func allIDs() async throws -> Set<String> { Self.allAssetIDs() }

    func copyItems(ids: [String]) async throws -> [CopyItem] {
        Self.assets(withIDs: ids).map {
            CopyItem(id: $0.localIdentifier, fileName: Self.baseFileName(for: $0), creationDate: $0.creationDate)
        }
    }

    private static var exportDirectory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
    }

    /// Deletes exported files that a copy killed by the system (crash, out of memory, background
    /// time ending) didn't get to remove. Call only while no copy is running.
    func removeLeftoverExports() {
        try? FileManager.default.removeItem(at: Self.exportDirectory)
    }

    /// Exports every file of the asset (downloading from iCloud if needed): the original,
    /// any edited version, the edit recipe, and Live Photo videos.
    func export(_ item: CopyItem) async throws -> [ExportedFile] {
        guard let asset = Self.assets(withIDs: [item.id]).first else { throw CopyError.itemMissing }
        let files = Self.files(for: asset)
        guard !files.isEmpty else { throw CopyError.nothingToCopy }

        let dir = Self.exportDirectory
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        func tempURL(_ name: String) -> URL {
            dir.appendingPathComponent(UUID().uuidString + "-" + RemotePath.sanitizeFileName(name))
        }

        var entries: [(role: AssetFileRole, originalFilename: String, url: URL)] = []
        do {
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true // originals may be in iCloud only
            for f in files {
                let url = tempURL(f.resource.originalFilename)
                try await PHAssetResourceManager.default().writeData(for: f.resource, toFile: url, options: options)
                entries.append((f.role, f.resource.originalFilename, url))
            }

            // Edited, but Photos has not stored a rendered file (e.g. slo-mo, or a render
            // that was never saved): ask Photos to render the current version.
            let edited = files.contains { $0.role == .adjustments }
            if edited, !files.contains(where: { $0.role == .edited }) {
                let rendered = asset.mediaType == .video
                    ? try await renderVideo(asset, to: tempURL("render.mov"))
                    : try await renderPhoto(asset, dir: dir)
                entries.append((.edited, rendered.lastPathComponent, rendered))
            }
        } catch {
            entries.forEach { try? FileManager.default.removeItem(at: $0.url) }
            throw error
        }

        let names = AssetFileNaming.names(base: item.fileName, files: entries.map { ($0.role, $0.originalFilename) })
        return zip(entries, names).map { ExportedFile(localURL: $0.url, fileName: $1) }
    }

    private func renderPhoto(_ asset: PHAsset, dir: URL) async throws -> URL {
        let options = PHImageRequestOptions()
        options.version = .current
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        options.isSynchronous = false
        let (data, uti): (Data, String?) = try await withCheckedThrowingContinuation { cont in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, uti, _, info in
                if let data {
                    cont.resume(returning: (data, uti))
                } else {
                    let err = (info?[PHImageErrorKey] as? Error)?.localizedDescription
                    cont.resume(throwing: CopyError.renderFailed(detail: err))
                }
            }
        }
        let ext = uti.flatMap { UTType($0)?.preferredFilenameExtension } ?? "jpg"
        let url = dir.appendingPathComponent(UUID().uuidString + "-render." + ext)
        try data.write(to: url)
        return url
    }

    private func renderVideo(_ asset: PHAsset, to url: URL) async throws -> URL {
        let options = PHVideoRequestOptions()
        options.version = .current
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        let session: AVAssetExportSession = try await withCheckedThrowingContinuation { cont in
            PHImageManager.default().requestExportSession(forVideo: asset, options: options,
                                                          exportPreset: AVAssetExportPresetHighestQuality) { session, info in
                if let session {
                    cont.resume(returning: session)
                } else {
                    let err = (info?[PHImageErrorKey] as? Error)?.localizedDescription
                    cont.resume(throwing: CopyError.renderFailed(detail: err))
                }
            }
        }
        if #available(iOS 18, *) {
            try await session.export(to: url, as: .mov)
        } else {
            session.outputURL = url
            session.outputFileType = .mov
            await session.export()
            if session.status != .completed {
                throw CopyError.renderFailed(detail: session.error?.localizedDescription)
            }
        }
        return url
    }
}

/// Calls `fire` once changes have stopped arriving for `settle`.
private final class LibraryChangeObserver: NSObject, PHPhotoLibraryChangeObserver, @unchecked Sendable {
    private let settle: Duration
    private let fire: @Sendable () -> Void
    private let lock = NSLock()
    private var pending: Task<Void, Never>?

    init(settle: Duration, fire: @escaping @Sendable () -> Void) {
        self.settle = settle
        self.fire = fire
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        lock.withLock {
            pending?.cancel()
            pending = Task { [settle, fire] in
                try? await Task.sleep(for: settle)
                if !Task.isCancelled { fire() }
            }
        }
    }

    func cancel() {
        lock.withLock { pending?.cancel() }
    }
}
