import SwiftUI

/// Deletes every copied photo and video from the device. The only criterion is that the
/// item was copied (and verified at the time). The destination is not consulted or changed.
/// The steps and their state live in `PhotosController`.
struct DeleteCopiedView: View {
    @EnvironmentObject var controller: PhotosController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = controller.delete
        NavigationStack {
            Form {
                Section {
                    Text("\(controller.copiedCount) photos and videos on this device have been copied.")
                } footer: {
                    Text("All of them are deleted from this device. The destination is not checked or changed. iOS asks you to confirm and keeps deleted items in “Recently Deleted” for 30 days.")
                }
                Section {
                    Button(role: .destructive) {
                        Task { await controller.startDelete() }
                    } label: {
                        Text("Delete \(controller.copiedCount) from this device")
                    }
                    .disabled(state.working || controller.copiedCount == 0)
                }
                if state.working { Section { ProgressView("Deleting…") } }
                if let error = state.error { Section { Text(error.text).foregroundStyle(.red) } }
                if let done = state.done { Section { Text(done.text).foregroundStyle(.green) } }
            }
            .navigationTitle("Delete copied photos")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Close") { dismiss() } }
        }
        .onDisappear { controller.resetDelete() }
    }
}
