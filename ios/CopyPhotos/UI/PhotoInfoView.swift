import CopyCore
import MapKit
import Photos
import SwiftUI

/// The Info panel, laid out like the one in Photos: date, a card with the file and
/// camera details, the map, then what CopyPhotos knows (the files it copies and where they went).
struct PhotoInfoView: View {
    let asset: PHAsset
    /// Whether the photo is in the copy history; the record is read again when this changes.
    let isCopied: Bool
    @EnvironmentObject private var controller: PhotoDetailsController
    @Environment(\.openURL) private var openURL

    private var id: String { asset.localIdentifier }
    private var detail: PhotoInfoLoader.Detail? { controller.detail }
    private var place: String? { placeName(controller.place) }

    var body: some View {
        ScrollView {
            if let info = controller.info {
                VStack(alignment: .leading, spacing: 16) {
                    header(info)
                    cameraCard(info)
                    locationCard(info)
                    copyCard(info)
                }
                .padding()
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
            }
        }
        .task(id: id) { await controller.load(asset) }
        .onChange(of: isCopied) { Task { await controller.reloadRecord() } }
    }

    // MARK: Header

    private func header(_ info: PhotoInfo) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let date = info.date {
                Text(dateLine(date, zone: info.takenTimeZone)).font(.headline)
                if let zone = info.takenTimeZone {
                    Text("Taken in \(zone.localizedName(for: .shortGeneric, locale: .current) ?? zone.identifier)")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Text("No date").font(.headline).foregroundStyle(.secondary)
            }
            let tags = info.kinds.map(\.label)
                + (info.isEdited ? [String(localized: "Edited")] : [])
                + (info.isFavorite ? [String(localized: "Favorite")] : [])
                + (info.isHidden ? [String(localized: "Hidden")] : [])
            if !tags.isEmpty {
                Text(tags.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    /// "Friday • 6 November 2026 • 14:32", in the time zone the photo was taken in.
    private func dateLine(_ date: Date, zone: TimeZone?) -> String {
        var day = Date.FormatStyle().weekday(.wide)
        var full = Date.FormatStyle(date: .long, time: .omitted)
        var time = Date.FormatStyle(date: .omitted, time: .shortened)
        if let zone { day.timeZone = zone; full.timeZone = zone; time.timeZone = zone }
        return [date.formatted(day), date.formatted(full), date.formatted(time)].joined(separator: " • ")
    }

    // MARK: Camera

    private func cameraCard(_ info: PhotoInfo) -> some View {
        Card {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(info.fileName)
                        .font(.subheadline.weight(.semibold))
                        .textSelection(.enabled)
                        .lineLimit(2)
                    Spacer()
                    if let format = info.format { Badge(text: format) }
                }
                if let camera = info.camera {
                    Text(camera).font(.subheadline)
                } else if detail == .loaded {
                    Text(info.kinds.contains(.screenshot) ? AssetKind.screenshot.label : String(localized: "No camera information"))
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Group {
                    if let lens = lensLine(info) { Text(lens) }
                    Text(sizeLine(info))
                    if let video = videoLine(info) { Text(video) }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                cloudNotice
            }
            .padding(12)

            if info.hasExposureDetails {
                Divider()
                ExposureStrip(values: [
                    info.iso.map(MetadataText.iso),
                    (info.focalLength35 ?? info.focalLength).flatMap(MetadataText.focalLength),
                    info.exposureBias.flatMap(MetadataText.exposureBias),
                    info.fNumber.flatMap(MetadataText.fNumber),
                    info.exposureTime.flatMap(MetadataText.exposureTime),
                ])
            }
        }
    }

    @ViewBuilder private var cloudNotice: some View {
        switch detail {
        case .none:
            ProgressView().controlSize(.small).padding(.top, 4)
        case .inCloud?:
            Button {
                Task { await controller.downloadDetails() }
            } label: {
                if controller.downloading {
                    HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Downloading from iCloud…") }
                } else {
                    Label("Camera details are in iCloud. Download", systemImage: "icloud.and.arrow.down")
                }
            }
            .font(.footnote)
            .disabled(controller.downloading)
            .padding(.top, 4)
        default:
            EmptyView()
        }
    }

    private func lensLine(_ info: PhotoInfo) -> String? {
        var parts: [String] = []
        if let lens = info.lens { parts.append(lens) }
        if info.focalLength35 != nil, let actual = info.focalLength.flatMap(MetadataText.focalLength) {
            parts.append(String(localized: "\(actual) actual", comment: "Focal length of the lens, after the 35 mm equivalent"))
        }
        if info.flashFired == true { parts.append(String(localized: "Flash")) }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    /// "12 MP • 4032 × 3024 • 2.1 MB" or, for videos, "4K • 3840 × 2160 • 30 fps • 210 MB".
    private func sizeLine(_ info: PhotoInfo) -> String {
        var parts: [String] = []
        let name = info.isVideo
            ? MetadataText.videoResolution(width: info.width, height: info.height)
            : MetadataText.megapixels(width: info.width, height: info.height)
        if let name { parts.append(name) }
        if info.width > 0, info.height > 0 { parts.append(String(localized: "\(info.width) × \(info.height)", comment: "Pixel dimensions")) }
        if let fps = info.frameRate.flatMap(MetadataText.frameRate) { parts.append(fps) }
        if let size = info.fileSize { parts.append(formatBytes(size)) }
        return parts.joined(separator: " • ")
    }

    private func videoLine(_ info: PhotoInfo) -> String? {
        guard info.isVideo else { return nil }
        let parts = [info.codec, info.duration.flatMap(MetadataText.duration)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    // MARK: Location

    @ViewBuilder
    private func locationCard(_ info: PhotoInfo) -> some View {
        if let location = info.location {
            let coordinate = location.coordinate
            Card {
                Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, latitudinalMeters: 1500, longitudinalMeters: 1500)),
                    interactionModes: []) {
                    Marker(place ?? "", coordinate: coordinate)
                }
                .id("\(coordinate.latitude),\(coordinate.longitude)")
                .frame(height: 170)
                .onTapGesture { openInMaps(coordinate) }

                Button { openInMaps(coordinate) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(place ?? String(localized: "Location")).font(.subheadline).foregroundStyle(.primary)
                            Text(MetadataText.coordinate(latitude: coordinate.latitude, longitude: coordinate.longitude)
                                 + (location.verticalAccuracy >= 0 ? " • " + String(localized: "\(Int(location.altitude.rounded())) m", comment: "Altitude in metres") : ""))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "arrow.up.forward.app").foregroundStyle(.secondary)
                    }
                    .padding(12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens in Maps")
            }
        } else {
            Card {
                Label("No location information", systemImage: "location.slash")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
        }
    }

    private func openInMaps(_ c: CLLocationCoordinate2D) {
        var comps = URLComponents(string: "https://maps.apple.com/")!
        comps.queryItems = [URLQueryItem(name: "ll", value: "\(c.latitude),\(c.longitude)"),
                            URLQueryItem(name: "q", value: place ?? String(localized: "Photo"))]
        if let url = comps.url { openURL(url) }
    }

    // MARK: CopyPhotos

    private func copyCard(_ info: PhotoInfo) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("CopyPhotos").font(.footnote.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            Card {
                VStack(alignment: .leading, spacing: 6) {
                    if let record = controller.record {
                        Label("Copied and verified", systemImage: "checkmark.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.copied)
                        Text("Copied \(record.copiedAt.formatted(date: .abbreviated, time: .shortened))")
                            .font(.footnote).foregroundStyle(.secondary)
                        ForEach(record.files, id: \.remotePath) { f in
                            HStack(alignment: .firstTextBaseline) {
                                Text(verbatim: "\(record.destination)/\(f.remotePath)").font(.caption.monospaced()).textSelection(.enabled)
                                Spacer()
                                Text(formatBytes(f.size))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        Label("Not copied yet", systemImage: "circle.dashed")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(info.files.count == 1 ? "This file will be copied:" : "These \(info.files.count) files will be copied:")
                            .font(.footnote).foregroundStyle(.secondary)
                        ForEach(info.files) { f in
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(f.name).font(.caption.monospaced()).textSelection(.enabled)
                                    Text(f.role.label(isVideo: info.isVideo)).font(.caption2).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if let size = f.size {
                                    Text(formatBytes(size))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
            }
        }
    }
}

// MARK: - Pieces

private struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct Badge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
    }
}

/// ISO 80 | 24 mm | 0 ev | ƒ1.78 | 1/120 s, as in Photos.
private struct ExposureStrip: View {
    let values: [String?]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(values.enumerated()), id: \.offset) { i, value in
                if i > 0 { Divider().frame(height: 18) }
                Text(value ?? "—")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(value == nil ? .tertiary : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 10)
    }
}
