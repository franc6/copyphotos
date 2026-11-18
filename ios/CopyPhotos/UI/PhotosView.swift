import CopyCore
import Photos
import SwiftUI

struct PhotosView: View {
    @EnvironmentObject var controller: PhotosController

    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var confirmCopyAgain = false
    @State private var confirmCopyAll = false
    @State private var confirmForget = false
    @State private var showDelete = false
    @State private var showFailures = false
    @State private var viewing: ViewerTarget?

    /// The photo opened in the viewer, and the list it pages through (as filtered when opened).
    struct ViewerTarget: Identifiable {
        let id: String
        let assets: [PHAsset]
        var showInfo = false
    }

    var body: some View {
        Group {
            switch controller.access {
            case .full, .limited:
                content
            case .notDetermined:
                permissionPrompt
            case .none:
                ContentUnavailableView(
                    "No photo access", systemImage: "lock",
                    description: Text("Allow full photo library access in Settings so CopyPhotos can copy and, if you ask, delete photos."))
            }
        }
        .navigationTitle("CopyPhotos")
    }

    private var permissionPrompt: some View {
        VStack(spacing: 16) {
            Image(systemName: "photo.stack").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("CopyPhotos needs access to your photo library to copy photos to your network share.")
                .multilineTextAlignment(.center)
            Button("Allow access") { Task { await controller.requestAccess() } }
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }

    private var content: some View {
        VStack(spacing: 0) {
            header
            Picker("Filter", selection: $controller.filter) {
                ForEach(PhotosController.Filter.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.bottom, 8)

            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 2)], spacing: 2) {
                    let visible = controller.visible
                    ForEach(visible, id: \.localIdentifier) { asset in
                        let id = asset.localIdentifier
                        ThumbnailCell(asset: asset,
                                      copied: controller.isCopied(asset),
                                      selected: selecting ? selection.contains(id) : nil)
                            .onTapGesture {
                                guard selecting else {
                                    viewing = ViewerTarget(id: id, assets: visible)
                                    return
                                }
                                if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
                            }
                            .contextMenu {
                                Button { viewing = ViewerTarget(id: id, assets: visible, showInfo: true) } label: {
                                    Label("Show Info", systemImage: "info.circle")
                                }
                            }
                    }
                }
            }

            if let p = controller.runState.progress {
                CopyProgressBar(progress: p) { controller.cancel() }
            } else if let r = controller.banner {
                ResultBannerView(banner: r, showFailures: { showFailures = true }) { controller.dismissBanner() }
            }
            actionBar
        }
        .toolbar { toolbar }
        .sheet(isPresented: $showDelete) { DeleteCopiedView() }
        .fullScreenCover(item: $viewing) { target in
            PhotoViewer(assets: target.assets, startID: target.id, showInfo: target.showInfo)
        }
        .sheet(isPresented: $showFailures) { FailuresView(failures: controller.banner?.failures ?? []) }
        .confirmationDialog("Copy again?", isPresented: $confirmCopyAgain, titleVisibility: .visible) {
            Button("Copy \(selection.count) photos") { copySelection() }
        } message: {
            let again = selection.filter { controller.copiedIDs.contains($0) }.count
            Text("\(again) of the selected photos were already copied. They will be copied again.")
        }
        .confirmationDialog("Copy every photo again?", isPresented: $confirmCopyAll, titleVisibility: .visible) {
            Button("Copy all \(controller.assets.count) photos") { controller.copyAllAgain() }
        } message: {
            Text("Files already on the destination with the same size and contents are not copied again. If a different file has the name, the copy gets a numbered name.")
        }
        .confirmationDialog("Forget copy history?", isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Forget history", role: .destructive) { Task { await controller.forgetHistory() } }
        } message: {
            Text("All photos will be treated as not copied, so automatic copying will copy them again. Nothing is deleted.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Label {
                    if let url = controller.destinationURL { Text(verbatim: url) } else { Text("No destination configured") }
                } icon: {
                    Image(systemName: "externaldrive")
                }
                    .font(.footnote)
                    .foregroundStyle(controller.destinationURL != nil ? Color.secondary : Color.orange)
                    .lineLimit(1)
                Spacer()
            }
            Text(joined([String(localized: "\(controller.assets.count) on device"),
                         String(localized: "\(controller.copiedCount) copied"),
                         String(localized: "\(controller.uncopiedCount) not yet copied")]))
                .font(.subheadline)
            if controller.access == .limited {
                Text("Limited access: only photos you selected are visible. Grant full access in Settings to copy everything.")
                    .font(.caption).foregroundStyle(.orange)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private var actionBar: some View {
        HStack {
            if selecting {
                Button("Copy \(selection.count) selected") {
                    if selection.contains(where: controller.copiedIDs.contains) {
                        confirmCopyAgain = true
                    } else {
                        copySelection()
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(selection.isEmpty || !controller.canCopy)
            } else {
                Button {
                    controller.copyNew()
                } label: {
                    Label("Copy \(controller.uncopiedCount) new", systemImage: "arrow.up.doc.on.clipboard")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!controller.canCopy || controller.uncopiedCount == 0)
            }
        }
        .padding()
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button(selecting ? "Done" : "Select") {
                selecting.toggle()
                selection.removeAll()
            }
        }
        if selecting {
            let visible = controller.visible
            ToolbarItem(placement: .topBarTrailing) {
                Button(selection.count == visible.count ? "Select none" : "Select all") {
                    if selection.count == visible.count { selection.removeAll() } else { selection = Set(visible.map(\.localIdentifier)) }
                }
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { confirmCopyAll = true } label: { Label("Copy all again…", systemImage: "arrow.clockwise") }
                        .disabled(!controller.canCopy)
                    Button(role: .destructive) { showDelete = true } label: {
                        Label("Delete copied photos from device…", systemImage: "trash")
                    }
                    .disabled(controller.isRunning || controller.copiedCount == 0)
                    Divider()
                    Button(role: .destructive) { confirmForget = true } label: {
                        Label("Forget copy history…", systemImage: "clock.arrow.circlepath")
                    }
                    .disabled(controller.isRunning)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }

    private func copySelection() {
        controller.copy(ids: Array(selection))
        selecting = false
        selection.removeAll()
    }
}

// MARK: - Cells & bars

struct ThumbnailCell: View {
    let asset: PHAsset
    let copied: Bool
    /// nil when not in selection mode.
    let selected: Bool?
    @State private var image: UIImage?
    /// Show the whole image scaled to fit, because its cropped centre would look blank.
    @State private var fit = false

    var body: some View {
        Color(.secondarySystemBackground)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    if fit {
                        Image(uiImage: image).resizable().scaledToFit()
                    } else {
                        Image(uiImage: image).resizable().scaledToFill()
                    }
                }
            }
            .clipped()
            .overlay(alignment: .bottomTrailing) {
                if copied {
                    // White check on a green disc, with a white rim so it shows on any photo (same as Android).
                    Image(systemName: "checkmark.circle.fill")
                        .font(.body)
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Color.copied)
                        .background(Circle().fill(.white).padding(-1.5))
                        .padding(5)
                        .accessibilityLabel("Copied")
                }
            }
            .overlay(alignment: .bottomLeading) {
                if asset.mediaType == .video {
                    Image(systemName: "video.fill").font(.caption).foregroundStyle(.white).padding(6).shadow(radius: 2)
                }
            }
            .overlay(alignment: .topTrailing) {
                if let selected {
                    Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(selected ? Color.accentColor : Color.white)
                        .shadow(radius: 2)
                        .padding(4)
                }
            }
            .contentShape(Rectangle())
            .task(id: asset.localIdentifier) { await load() }
    }

    private func load() async {
        for await img in PhotoLibrary.images(for: asset, side: 200, contentMode: .aspectFill) {
            fit = cropLooksBlank(img)
            image = img
        }
    }
}

struct CopyProgressBar: View {
    let progress: CopyProgress
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Copying \(min(progress.completed + progress.failed + 1, progress.total)) of \(progress.total)")
                    .font(.subheadline.bold())
                Spacer()
                Button("Stop", role: .destructive, action: cancel)
            }
            ProgressView(value: Double(progress.completed + progress.failed), total: Double(max(progress.total, 1)))
            if let name = progress.currentName {
                Text(joined([name, formatBytes(progress.currentBytes)]))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            if progress.failed > 0 {
                Text("\(progress.failed) failed").font(.caption).foregroundStyle(.red)
            }
        }
        .padding()
        .background(.bar)
    }
}

struct ResultBannerView: View {
    let banner: ResultBanner
    let showFailures: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: banner.isError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .foregroundStyle(banner.isError ? .orange : .green)
            VStack(alignment: .leading, spacing: 2) {
                Text(banner.title).font(.subheadline.bold())
                Text(banner.detail).font(.caption).foregroundStyle(.secondary)
                if !banner.failures.isEmpty {
                    Button("Show errors", action: showFailures).font(.caption)
                }
            }
            Spacer()
            Button(action: dismiss) { Image(systemName: "xmark") }.foregroundStyle(.secondary)
        }
        .padding()
        .background(.bar)
    }
}

struct FailuresView: View {
    let failures: [CopyFailure]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(failures.indices, id: \.self) { Text(failures[$0].text).font(.callout) }
                .navigationTitle("Errors")
                .toolbar { Button("Done") { dismiss() } }
        }
    }
}
