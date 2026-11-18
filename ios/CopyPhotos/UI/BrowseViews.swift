import CopyCore
import SwiftUI

/// One browser for the whole destination: the server's shares, then the folders inside them.
/// "Choose" sets the share and the folder together, like a folder picker on a computer.
struct LocationBrowser: View {
    @ObservedObject var controller: DestinationController
    @State private var stack: [BrowseLocation]
    @Environment(\.dismiss) private var dismiss

    init(controller: DestinationController) {
        self.controller = controller
        // Open at the folder that is already selected, with every parent folder on the back stack.
        _stack = State(initialValue: controller.browseStack())
    }

    var body: some View {
        NavigationStack(path: $stack) {
            BrowseLevel(controller: controller, location: nil, stack: $stack)
                .navigationDestination(for: BrowseLocation.self) { loc in
                    BrowseLevel(controller: controller, location: loc, stack: $stack)
                }
                .toolbar { cancelButton }
        }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .onDisappear { controller.closeBrowser() }
    }

    private var cancelButton: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
    }

    private var bottomBar: some View {
        VStack(spacing: 8) {
            Text(controller.displayURL(stack.last))
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                if let loc = stack.last {
                    controller.choose(loc)
                    dismiss()
                }
            } label: {
                Text(stack.last.map { String(localized: "Choose “\($0.name)”") } ?? String(localized: "Select a share"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(stack.isEmpty)
        }
        .padding(.horizontal)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .background(.bar)
    }
}

/// One level of the browser: the shares on the server (`location` nil), or a folder inside a
/// share with subfolders first (tap to open), then files, dimmed.
private struct BrowseLevel: View {
    @ObservedObject var controller: DestinationController
    let location: BrowseLocation?
    @Binding var stack: [BrowseLocation]

    /// nil while loading.
    @State private var listing: BrowseListing?
    @State private var createError: FailureReason?
    @State private var askName = false
    @State private var typedName = ""

    private var isMissing: Bool {
        if case .missing? = listing { return true }
        return false
    }

    var body: some View {
        List {
            listingRows
            if let createError { Text(createError.text).foregroundStyle(.red) }
            if location == nil {
                Section {
                    Button("Open a share by name…") { askName = true }
                } footer: {
                    Text("Use this for shares the server does not list.")
                }
            }
        }
        .navigationTitle(location?.name ?? controller.config.host)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if location != nil {
                ToolbarItem(placement: .primaryAction) {
                    Button { askName = true } label: { Label("New Folder", systemImage: "folder.badge.plus") }
                        .disabled(isMissing)
                }
            }
        }
        .alert(nameTitle, isPresented: $askName) { nameFields }
        .task { await load() }
        // Keep the rows while refreshing: swapping the List's content for a spinner mid-pull
        // ends the refresh and cancels its task, so the new listing never arrived.
        .refreshable { listing = await controller.listing(for: location) }
    }

    /// The listing's rows, or what stands in for them while loading, on failure, or for a
    /// folder that doesn't exist yet.
    @ViewBuilder
    private var listingRows: some View {
        switch listing {
        case nil:
            if location == nil { ProgressView("Connecting to \(controller.config.host)…") } else { ProgressView() }
        case .failed(let reason)?:
            Text(reason.text).foregroundStyle(.red)
        case .missing?:
            Text("This folder does not exist yet. It is created when the first photo is copied, or tap New Folder in the folder above it.")
                .foregroundStyle(.secondary)
        case .entries(let entries)?:
            ForEach(entries) { row($0) }
            if entries.isEmpty {
                Text(location == nil ? "The server did not list any shares." : "Empty folder").foregroundStyle(.secondary)
            }
        }
    }

    /// The name prompt's title: a new folder here, or a share to open.
    private var nameTitle: String {
        location.map { String(localized: "New folder in “\($0.name)”") } ?? String(localized: "Open share")
    }

    /// The name prompt's field and buttons.
    @ViewBuilder
    private var nameFields: some View {
        TextField(location == nil ? "Share name" : "Name", text: $typedName)
            .textInputAutocapitalization(location == nil ? .never : .sentences)
            .autocorrectionDisabled()
        Button(location == nil ? "Open" : "Create") { Task { await confirmName() } }
        Button("Cancel", role: .cancel) { typedName = "" }
    }

    /// One share or folder entry.
    /// - Parameter entry: The entry.
    /// - Returns: A link that opens a share or subfolder, or a dimmed file.
    @ViewBuilder
    private func row(_ entry: RemoteEntry) -> some View {
        if let location {
            if entry.isDirectory {
                NavigationLink(value: location.child(entry.name)) {
                    Label(entry.name, systemImage: "folder.fill")
                }
            } else {
                Label(entry.name, systemImage: "doc").foregroundStyle(.secondary)
            }
        } else {
            NavigationLink(value: BrowseLocation(share: entry.name, path: "")) {
                Label(entry.name, systemImage: "externaldrive.connected.to.line.below")
            }
        }
    }

    private func load() async {
        listing = nil
        listing = await controller.listing(for: location)
    }

    private func confirmName() async {
        let name = typedName
        typedName = ""
        guard let location else {
            // Shares level: open the typed share.
            let share = DestinationConfig.normalizedShare(name)
            if !share.isEmpty { stack = [BrowseLocation(share: share, path: "")] }
            return
        }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        do {
            createError = nil
            let child = try await controller.createFolder(trimmed, in: location)
            await load()
            stack.append(child) // open the new folder, as Finder's save panel does
        } catch {
            createError = FailureReason(error)
        }
    }
}
