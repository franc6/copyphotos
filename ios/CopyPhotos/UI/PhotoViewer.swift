import CopyCore
import Photos
import SwiftUI

/// Full-screen photo viewer, like Photos: swipe between photos, tap to hide the bars,
/// swipe down to close, and ⓘ (or swipe up) for the Info panel.
struct PhotoViewer: View {
    @EnvironmentObject private var controller: PhotosController
    let assets: [PHAsset]
    @State private var currentID: String?
    @State private var showInfo: Bool
    @State private var chromeHidden = false
    @Environment(\.dismiss) private var dismiss

    init(assets: [PHAsset], startID: String, showInfo: Bool = false) {
        self.assets = assets
        _currentID = State(initialValue: startID)
        _showInfo = State(initialValue: showInfo)
    }

    private var current: PHAsset? {
        assets.first { $0.localIdentifier == currentID } ?? assets.first
    }

    var body: some View {
        NavigationStack {
            pager
                .background(chromeHidden ? Color.black : Color(.systemBackground))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
                .toolbar(chromeHidden ? .hidden : .visible, for: .navigationBar, .bottomBar)
                .statusBarHidden(chromeHidden)
                .animation(.easeInOut(duration: 0.2), value: chromeHidden)
        }
        .sheet(isPresented: $showInfo) {
            if let current {
                PhotoInfoView(asset: current, isCopied: controller.isCopied(current))
                    .presentationDetents([.medium, .large])
                    // Keep swiping between photos while Info is open; it follows the photo shown.
                    .presentationBackgroundInteraction(.enabled(upThrough: .medium))
                    .presentationContentInteraction(.scrolls)
            }
        }
    }

    private var pager: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(assets, id: \.localIdentifier) { asset in
                        FullImage(asset: asset)
                            .containerRelativeFrame([.horizontal, .vertical])
                            .id(asset.localIdentifier)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $currentID)
            .scrollIndicators(.hidden)
            .ignoresSafeArea()
            .onAppear { if let currentID { proxy.scrollTo(currentID) } }
            .onTapGesture { chromeHidden.toggle() }
            .simultaneousGesture(
                DragGesture(minimumDistance: 30).onEnded { v in
                    let dx = v.translation.width, dy = v.translation.height
                    guard abs(dy) > abs(dx) * 1.5 else { return } // horizontal swipes page instead
                    if dy < -60 { showInfo = true } else if dy > 100 { dismiss() }
                }
            )
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.backward").fontWeight(.semibold)
            }
            .accessibilityLabel("Back")
        }
        ToolbarItem(placement: .principal) {
            if let date = current?.creationDate {
                VStack(spacing: 0) {
                    Text(date.formatted(date: .long, time: .omitted)).font(.subheadline.weight(.semibold))
                    Text(date.formatted(date: .omitted, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        ToolbarItemGroup(placement: .bottomBar) {
            Spacer()
            Button { showInfo.toggle() } label: {
                Image(systemName: showInfo ? "info.circle.fill" : "info.circle")
            }
            .accessibilityLabel("Info")
            Spacer()
        }
    }
}

/// One photo, scaled to fit the screen. Videos show their poster frame and length.
private struct FullImage: View {
    let asset: PHAsset
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                ProgressView()
            }
            if asset.mediaType == .video {
                Label(MetadataText.duration(asset.duration) ?? "", systemImage: "video.fill")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.ultraThinMaterial, in: Capsule())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.horizontal)
                    .padding(.bottom, 100)
            }
        }
        .task(id: asset.localIdentifier) { await load() }
    }

    private func load() async {
        for await img in PhotoLibrary.images(for: asset, side: 2048, contentMode: .aspectFit) { image = img }
    }
}
