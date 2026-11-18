import SwiftUI

extension Color {
    /// The "copied" check mark and label (same green as Android).
    static let copied = Color(red: 0x2E / 255, green: 0x7D / 255, blue: 0x32 / 255)
}

extension View {
    /// For names and addresses: no automatic capitals or corrections.
    func plainTextInput() -> some View {
        textInputAutocapitalization(.never).autocorrectionDisabled()
    }
}

/// "2.1 MB", as Files shows file sizes.
func formatBytes(_ bytes: some BinaryInteger) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
}

/// A text field that shows exactly what is typed but stores it normalized, so the value
/// is always clean without fighting the user's cursor (e.g. a space typed before the next word).
struct NormalizedTextField: View {
    let title: String
    @Binding var value: String
    let normalize: (String) -> String
    @State private var text = ""

    init(_ title: String, value: Binding<String>, normalize: @escaping (String) -> String) {
        self.title = title
        _value = value
        self.normalize = normalize
    }

    var body: some View {
        TextField(title, text: $text)
            .onAppear { text = value }
            .onChange(of: text) { value = normalize(text) }
            // Set elsewhere (e.g. chosen in the browser): show the new value.
            .onChange(of: value) { if normalize(text) != value { text = value } }
    }
}
