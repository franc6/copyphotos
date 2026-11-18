import AVFoundation
import CopyCore
import CoreLocation
import ImageIO
import MapKit
import Photos
import UniformTypeIdentifiers

/// Everything the Info panel shows about one photo or video. Fields are nil when the
/// file doesn't carry them (a screenshot has no camera, many photos have no location).
struct PhotoInfo {
    struct File: Identifiable {
        let id = UUID()
        let role: AssetFileRole
        let name: String
        let size: Int64?
    }

    var fileName: String
    /// "HEIF", "JPEG", "RAW", "HEVC"… shown as a badge like Photos does.
    var format: String?
    var fileSize: Int64?
    var files: [File] = []

    var date: Date?
    /// Time zone the photo was taken in (from EXIF), when it differs from the device's.
    var takenTimeZone: TimeZone?

    var width: Int
    var height: Int
    var isVideo: Bool
    var duration: Double?
    var frameRate: Double?
    var codec: String?
    /// Live Photo, Portrait, Panorama, Screenshot, Burst, Slo-mo…
    var kinds: [AssetKind] = []
    var isEdited = false
    var isFavorite = false
    var isHidden = false

    var make: String?
    var model: String?
    var lens: String?
    var iso: Int?
    var focalLength: Double?
    var focalLength35: Double?
    var fNumber: Double?
    var exposureTime: Double?
    var exposureBias: Double?
    var flashFired: Bool?

    var location: CLLocation?

    /// "Apple iPhone 15 Pro".
    var camera: String? { CameraMetadata.cameraName(make: make, model: model) }

    var hasExposureDetails: Bool {
        iso != nil || focalLength != nil || fNumber != nil || exposureTime != nil || exposureBias != nil
    }
}

/// What kind of photo or video it is, as Photos tags it.
enum AssetKind {
    case livePhoto, portrait, panorama, hdr, screenshot, sloMo, timeLapse, cinematic, spatial, screenRecording, burst
}

private extension String {
    var trimmed: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines.union(.controlCharacters))
        return t.isEmpty ? nil : t
    }
}

/// Reads what a photo or video is from PhotoKit, its file and the geocoder, for the Info panel.
struct PhotoInfoLoader {
    /// Whether the original is on the device. Camera details are read from the original, and
    /// Photos-in-iCloud may only keep a preview locally; downloading is left to the user.
    enum Detail {
        case loaded
        case inCloud
        case unavailable
    }

    /// What PhotoKit knows without reading the file: instant.
    func basic(for asset: PHAsset) -> PhotoInfo {
        let files = PhotoLibrary.files(for: asset)
        let original = files.first { $0.role == .original }?.resource
        var info = PhotoInfo(fileName: PhotoLibrary.baseFileName(for: asset),
                             width: asset.pixelWidth, height: asset.pixelHeight,
                             isVideo: asset.mediaType == .video)
        info.format = original.flatMap { formatName(uti: $0.uniformTypeIdentifier, isRAWPair: files.contains { $0.role == .alternateOriginal }) }
        info.fileSize = original.flatMap(fileSize)
        info.date = asset.creationDate
        info.location = asset.location
        info.duration = asset.mediaType == .video ? asset.duration : nil
        info.isEdited = files.contains { $0.role == .adjustments || $0.role == .edited }
        info.isFavorite = asset.isFavorite
        info.isHidden = asset.isHidden
        info.kinds = kinds(of: asset)
        let names = AssetFileNaming.names(base: info.fileName,
                                          files: files.map { ($0.role, $0.resource.originalFilename) })
        info.files = zip(files, names).map { f, name in
            PhotoInfo.File(role: f.role, name: name, size: fileSize(f.resource))
        }
        return info
    }

    /// Adds camera, exposure and (for videos) codec details from the original file.
    /// With `allowNetwork` false, nothing is downloaded from iCloud.
    func details(for asset: PHAsset, into info: inout PhotoInfo, allowNetwork: Bool) async -> Detail {
        asset.mediaType == .video
            ? await videoDetails(asset, &info, allowNetwork: allowNetwork)
            : await photoDetails(asset, &info, allowNetwork: allowNetwork)
    }

    // MARK: Photos

    private func photoDetails(_ asset: PHAsset, _ info: inout PhotoInfo, allowNetwork: Bool) async -> Detail {
        let options = PHImageRequestOptions()
        options.version = .original // camera metadata lives in the original
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = allowNetwork
        let (data, inCloud): (Data?, Bool) = await request { done in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, info in done(data, info) }
        }
        guard let data else { return inCloud ? .inCloud : .unavailable }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else { return .unavailable }
        apply(props, to: &info)
        return .loaded
    }

    /// Adds what an image file's properties say about the camera and the shot.
    /// - Parameters:
    ///   - props: The image's properties, from ImageIO.
    ///   - info: The info to add to.
    private func apply(_ props: [CFString: Any], to info: inout PhotoInfo) {
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let exifAux = props[kCGImagePropertyExifAuxDictionary] as? [CFString: Any] ?? [:]
        info.make = tiff[kCGImagePropertyTIFFMake] as? String
        info.model = tiff[kCGImagePropertyTIFFModel] as? String
        info.lens = ((exif[kCGImagePropertyExifLensModel] as? String) ?? (exifAux[kCGImagePropertyExifAuxLensModel] as? String))?.trimmed
        applyExposure(exif, to: &info)
        applyTakenTimeZone(exif, to: &info)
        if let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue, w > 0, info.width == 0 { info.width = w }
        if let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue, h > 0, info.height == 0 { info.height = h }
    }

    /// Adds the exposure: ISO, focal length, aperture, shutter, bias and flash.
    /// - Parameters:
    ///   - exif: The image's EXIF dictionary.
    ///   - info: The info to add to.
    private func applyExposure(_ exif: [CFString: Any], to info: inout PhotoInfo) {
        func double(_ v: Any?) -> Double? { (v as? NSNumber)?.doubleValue }
        info.iso = ((exif[kCGImagePropertyExifISOSpeedRatings] as? [NSNumber])?.first).map { $0.intValue }
        info.focalLength = double(exif[kCGImagePropertyExifFocalLength])
        info.focalLength35 = double(exif[kCGImagePropertyExifFocalLenIn35mmFilm]).flatMap { $0 > 0 ? $0 : nil }
        info.fNumber = double(exif[kCGImagePropertyExifFNumber])
        info.exposureTime = double(exif[kCGImagePropertyExifExposureTime])
        info.exposureBias = double(exif[kCGImagePropertyExifExposureBiasValue])
        if let flash = (exif[kCGImagePropertyExifFlash] as? NSNumber)?.intValue { info.flashFired = flash & 1 == 1 }
    }

    /// Adds the time zone the photo was taken in, when it differs from the device's.
    /// - Parameters:
    ///   - exif: The image's EXIF dictionary.
    ///   - info: The info to add to.
    private func applyTakenTimeZone(_ exif: [CFString: Any], to info: inout PhotoInfo) {
        guard let offset = (exif[kCGImagePropertyExifOffsetTimeOriginal] as? String).flatMap(CameraMetadata.timeZoneOffset),
              offset != TimeZone.current.secondsFromGMT(for: info.date ?? Date()) else { return }
        info.takenTimeZone = TimeZone(secondsFromGMT: offset)
    }

    // MARK: Videos

    private func videoDetails(_ asset: PHAsset, _ info: inout PhotoInfo, allowNetwork: Bool) async -> Detail {
        let options = PHVideoRequestOptions()
        options.version = .original
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = allowNetwork
        let (av, inCloud): (AVAsset?, Bool) = await request { done in
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { av, _, info in done(av, info) }
        }
        guard let av else { return inCloud ? .inCloud : .unavailable }
        await applyVideoTrack(of: av, to: &info)
        await applyVideoMetadata(of: av, to: &info)
        return .loaded
    }

    /// Adds the video track's size, frame rate and codec.
    /// - Parameters:
    ///   - av: The video.
    ///   - info: The info to add to.
    private func applyVideoTrack(of av: AVAsset, to info: inout PhotoInfo) async {
        guard let track = try? await av.loadTracks(withMediaType: .video).first,
              let (size, transform, fps, formats) = try? await track.load(.naturalSize, .preferredTransform,
                                                                          .nominalFrameRate, .formatDescriptions)
        else { return }
        let r = CGRect(origin: .zero, size: size).applying(transform)
        if info.width == 0 { info.width = Int(abs(r.width).rounded()) }
        if info.height == 0 { info.height = Int(abs(r.height).rounded()) }
        info.frameRate = fps > 0 ? Double(fps) : nil
        info.codec = formats.first.map { codecName(CMFormatDescriptionGetMediaSubType($0)) }
    }

    /// Adds the camera make, model and lens that QuickTime metadata records.
    /// - Parameters:
    ///   - av: The video.
    ///   - info: The info to add to.
    private func applyVideoMetadata(of av: AVAsset, to info: inout PhotoInfo) async {
        guard let items = try? await av.load(.metadata) else { return }
        func string(_ id: AVMetadataIdentifier) async -> String? {
            guard let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: id).first else { return nil }
            return try? await item.load(.stringValue)
        }
        info.make = await string(.quickTimeMetadataMake)
        info.model = await string(.quickTimeMetadataModel)
        if let lens = await string(AVMetadataIdentifier(rawValue: "mdta/com.apple.quicktime.camera.lens_model")) {
            info.lens = lens
        }
    }

    private func codecName(_ code: FourCharCode) -> String {
        switch code {
        case kCMVideoCodecType_HEVC, kCMVideoCodecType_HEVCWithAlpha: return "HEVC"
        case kCMVideoCodecType_H264: return "H.264"
        case kCMVideoCodecType_AppleProRes422, kCMVideoCodecType_AppleProRes422HQ, kCMVideoCodecType_AppleProRes422LT,
             kCMVideoCodecType_AppleProRes422Proxy, kCMVideoCodecType_AppleProRes4444, kCMVideoCodecType_AppleProRes4444XQ:
            return "ProRes"
        default:
            let chars = [24, 16, 8, 0].map { Character(UnicodeScalar(UInt8((code >> $0) & 0xFF))) }
            return String(chars).trimmingCharacters(in: .whitespaces).uppercased()
        }
    }

    /// Runs a PhotoKit request whose result handler gets the value and PhotoKit's info
    /// dictionary, and returns the value and whether the original is only in iCloud.
    private func request<T>(
        _ start: (@escaping (T?, [AnyHashable: Any]?) -> Void) -> Void
    ) async -> (T?, Bool) {
        await withCheckedContinuation { cont in
            start { value, info in
                cont.resume(returning: (value, (info?[PHImageResultIsInCloudKey] as? Bool) ?? false))
            }
        }
    }

    // MARK: Place name

    /// The parts of the place's name ("Cupertino", "California"), most specific first; empty
    /// when offline. The views join them.
    func placeName(for location: CLLocation) async -> [String] {
        if #available(iOS 26, *) {
            // The system's own one-line description of the place.
            guard let request = MKReverseGeocodingRequest(location: location),
                  let item = try? await request.mapItems.first else { return [] }
            return [item.addressRepresentations?.cityWithContext ?? item.name].compactMap { $0 }
        } else {
            guard let p = try? await CLGeocoder().reverseGeocodeLocation(location).first else { return [] }
            return CameraMetadata.placeNameParts(locality: p.locality, subAdministrativeArea: p.subAdministrativeArea,
                                                 administrativeArea: p.administrativeArea, country: p.country,
                                                 name: p.name ?? p.ocean)
        }
    }

    // MARK: Helpers

    /// Size in bytes as Photos records it. Not public API, so it is read defensively.
    private func fileSize(_ resource: PHAssetResource) -> Int64? {
        guard resource.responds(to: NSSelectorFromString("fileSize")) else { return nil }
        return (resource.value(forKey: "fileSize") as? NSNumber)?.int64Value
    }

    /// The short names of well-known file types, in the order they are checked.
    private static let formatNames: [(type: UTType, name: String)] = [
        (.rawImage, "RAW"), (.heic, "HEIF"), (.heif, "HEIF"), (.jpeg, "JPEG"), (.png, "PNG"), (.gif, "GIF"),
        (.tiff, "TIFF"), (.webP, "WebP"), (.quickTimeMovie, "MOV"), (.mpeg4Movie, "MP4"),
    ]

    /// The short name of a file's format ("HEIF", "RAW + JPEG").
    /// - Parameters:
    ///   - uti: The file's type identifier.
    ///   - isRAWPair: The file is the JPEG or HEIF of a RAW+JPEG pair.
    /// - Returns: The name, or nil if the type is unknown.
    private func formatName(uti: String, isRAWPair: Bool) -> String? {
        guard let type = UTType(uti) else { return nil }
        let name = Self.formatNames.first { type.conforms(to: $0.type) }?.name ?? type.preferredFilenameExtension?.uppercased()
        guard isRAWPair, name != "RAW" else { return name }
        return "RAW + " + (name ?? "JPEG")
    }

    private func kinds(of asset: PHAsset) -> [AssetKind] {
        let s = asset.mediaSubtypes
        let tags: [(Bool, AssetKind)] = [
            (s.contains(.photoLive), .livePhoto),
            (s.contains(.photoDepthEffect), .portrait),
            (s.contains(.photoPanorama), .panorama),
            (s.contains(.photoHDR), .hdr),
            (s.contains(.photoScreenshot), .screenshot),
            (s.contains(.videoHighFrameRate), .sloMo),
            (s.contains(.videoTimelapse), .timeLapse),
            (s.contains(.videoCinematic), .cinematic),
            (s.contains(.spatialMedia), .spatial),
            (s.contains(.videoScreenRecording), .screenRecording),
            (asset.representsBurst || asset.burstIdentifier != nil, .burst),
        ]
        return tags.filter(\.0).map(\.1)
    }
}
