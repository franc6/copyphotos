import CopyCore
import Photos

/// Controller for the photo Info panel: what the photo is, where it was taken, and whether
/// (and where) CopyPhotos copied it.
@MainActor
final class PhotoDetailsController: ObservableObject {
    @Published private(set) var info: PhotoInfo?
    /// nil while the camera details are loading.
    @Published private(set) var detail: PhotoInfoLoader.Detail?
    @Published private(set) var downloading = false
    /// The parts of the place name ("Cupertino", "California"); empty while unknown.
    @Published private(set) var place: [String] = []
    @Published private(set) var record: CopyRecord?

    private let service: CopyService
    private let loader: PhotoInfoLoader
    private var asset: PHAsset?

    /// - Parameters:
    ///   - service: Where the copy record comes from.
    ///   - loader: Reads what the photo is.
    init(service: CopyService, loader: PhotoInfoLoader) {
        self.service = service
        self.loader = loader
    }

    /// Shows `asset`: the basics at once, then camera details, the place name and the copy record.
    func load(_ asset: PHAsset) async {
        self.asset = asset
        var info = loader.basic(for: asset)
        self.info = info
        place = []
        detail = nil
        record = nil
        record = await service.record(for: asset.localIdentifier)
        detail = await loader.details(for: asset, into: &info, allowNetwork: false)
        guard self.asset == asset else { return }
        self.info = info
        if let location = info.location { place = await loader.placeName(for: location) }
    }

    /// Reads the copy record again (after the photo was copied or its history forgotten).
    func reloadRecord() async {
        guard let asset else { return }
        record = await service.record(for: asset.localIdentifier)
    }

    /// Downloads the original from iCloud to read its camera details.
    func downloadDetails() async {
        guard let asset, var current = info else { return }
        downloading = true
        defer { downloading = false }
        detail = await loader.details(for: asset, into: &current, allowNetwork: true)
        info = current
    }
}
