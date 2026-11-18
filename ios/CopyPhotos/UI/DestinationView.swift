import CopyCore
import SwiftUI

struct DestinationView: View {
    @EnvironmentObject var controller: DestinationController
    @State private var browsing = false
    @State private var manualEntry = false
    @State private var portText = ""
    /// Only what is typed here; the saved password is never loaded back into the field.
    @State private var passwordText = ""

    private var config: DestinationConfig { controller.config }

    var body: some View {
        Form {
            Section("SMB server") {
                NormalizedTextField("Host name or IP address", value: binding(\.host),
                                    normalize: DestinationConfig.normalizedHost)
                    .plainTextInput()
                    .keyboardType(.URL)
                TextField("Port (default 445)", text: $portText)
                    .keyboardType(.numberPad)
                    .onChange(of: portText) { controller.update { $0.port = Int(portText) } }
            }

            authSection

            Section {
                Button { browsing = true } label: {
                    HStack {
                        Image(systemName: "folder.fill").foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(locationTitle).foregroundStyle(.primary)
                            if !config.share.isEmpty {
                                Text(config.displayURL)
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.head)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                }
                .disabled(!config.hasServerAndSignIn)
                DisclosureGroup("Enter manually", isExpanded: $manualEntry) {
                    NormalizedTextField("Share name", value: binding(\.share),
                                        normalize: DestinationConfig.normalizedShare)
                        .plainTextInput()
                    TextField("Folder in the share (e.g. Photos/iPhone)", text: binding(\.path))
                        .plainTextInput()
                }
            } header: {
                Text("Location")
            } footer: {
                Text("Photos are copied straight into this folder, with no subfolders. It is created if it does not exist.")
            }

            Section("Copying") {
                Picker("Verify each copy", selection: binding(\.verifyMode)) {
                    ForEach(VerifyMode.allCases, id: \.self) { Text($0.label).tag($0) }
                }
            }

            Section {
                Toggle("Automatically copy new photos", isOn: binding(\.autoCopy))
                if config.autoCopy {
                    Toggle("Only while charging (background)", isOn: binding(\.autoCopyRequiresPower))
                }
            } header: {
                Text("Automation")
            } footer: {
                Text("New photos are copied when the app opens, when the library changes, and periodically in the background when iOS allows. Photos that were already copied are never copied again automatically.")
            }

            Section {
                Button {
                    Task { await controller.testConnection() }
                } label: {
                    HStack {
                        Text("Test connection")
                        if controller.testing { Spacer(); ProgressView() }
                    }
                }
                .disabled(controller.testing || !config.isComplete)
                if let result = controller.testResult {
                    let ok = result == .succeeded
                    Label(result.text(for: config), systemImage: ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                        .foregroundStyle(ok ? .green : .red)
                        .font(.callout)
                }
            }
        }
        .navigationTitle("Destination")
        .onAppear {
            portText = config.port.map(String.init) ?? ""
        }
        .sheet(isPresented: $browsing) {
            LocationBrowser(controller: controller)
        }
    }

    /// A binding that edits one setting through the controller.
    private func binding<T>(_ keyPath: WritableKeyPath<DestinationConfig, T>) -> Binding<T> {
        Binding(get: { controller.config[keyPath: keyPath] },
                set: { value in controller.update { $0[keyPath: keyPath] = value } })
    }

    private var locationTitle: String {
        if config.share.isEmpty {
            return config.hasServerAndSignIn ? String(localized: "Choose a folder…") : String(localized: "Enter the server and sign-in first")
        }
        return config.location.name
    }

    private var authSection: some View {
        Section {
            Picker("Sign in with", selection: binding(\.smbAuth)) {
                ForEach(SMBAuthMethod.allCases) { Text($0.label).tag($0) }
            }
            if config.smbAuth == .userPassword {
                TextField("Username", text: binding(\.username))
                    .plainTextInput()
                    .textContentType(.username)
                SecureField(controller.hasPassword ? LocalizedStringKey("Password saved (type to replace)") : "Password",
                            text: Binding(get: { passwordText }, set: { passwordText = $0; controller.setPassword($0) }))
                    .textContentType(.password)
                if controller.passwordNotSaved {
                    Text("The password couldn't be saved in the Keychain.")
                        .foregroundStyle(.red)
                }
                TextField("Domain / workgroup (optional)", text: binding(\.domain))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
            }
        } header: {
            Text("Authentication")
        } footer: {
            Text("Signing in with a password uses SMB 3 with NTLMv2, and files are encrypted on the way. Your password is stored in the iOS Keychain on this device.")
        }
    }
}
