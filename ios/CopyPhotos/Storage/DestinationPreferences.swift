import CopyCore
import Foundation

/// The `DestinationStore` on iOS: the settings in UserDefaults (as JSON), the SMB password in
/// the Keychain. `DestinationService` is its only user.
struct DestinationPreferences: DestinationStore {
    private static let key = "destination"

    /// Where the settings are kept.
    let defaults: UserDefaults
    /// Where the password is kept; shared with the connector, which reads it.
    let keychain: Keychain

    /// The saved settings.
    /// - Returns: The settings, or defaults if none are saved or they can't be decoded.
    func loadConfig() -> DestinationConfig {
        guard let data = defaults.data(forKey: Self.key),
              let config = try? JSONDecoder().decode(DestinationConfig.self, from: data) else {
            return DestinationConfig()
        }
        return config
    }

    /// Saves the settings.
    /// - Parameter config: The settings to save.
    func saveConfig(_ config: DestinationConfig) {
        if let data = try? JSONEncoder().encode(config) {
            defaults.set(data, forKey: Self.key)
        }
    }

    /// Whether a password is saved.
    var hasPassword: Bool { keychain.hasPassword }

    /// Saves the password; an empty one removes it.
    /// - Parameter password: The new password.
    /// - Throws: `Keychain.Failure` when the Keychain refuses it.
    func setPassword(_ password: String) throws {
        try keychain.setPassword(password)
    }
}
