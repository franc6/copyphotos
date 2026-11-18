import Foundation
import Security

/// One password in the Keychain, readable after first unlock so background copies can use it.
struct Keychain {
    /// A Keychain call that did not succeed.
    struct Failure: Error {
        /// The status the Keychain returned.
        let status: OSStatus
    }

    /// The Keychain service name the item is filed under.
    let service: String
    /// The Keychain account name of the item.
    let account: String

    /// Identifies the password item.
    private var item: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    /// Saves the password; an empty one removes it. If the Keychain refuses the change, the old
    /// password is still there.
    /// - Parameter password: The new password.
    /// - Throws: `Failure` when the Keychain refuses the change.
    func setPassword(_ password: String) throws {
        guard !password.isEmpty else {
            let status = SecItemDelete(item as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure(status: status) }
            return
        }
        let values: [String: Any] = [
            kSecValueData as String: Data(password.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(item as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(item.merging(values) { $1 } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw Failure(status: status) }
    }

    /// Whether a password is stored, without reading it.
    var hasPassword: Bool {
        SecItemCopyMatching(item as CFDictionary, nil) == errSecSuccess
    }

    /// The stored password.
    /// - Returns: The password, or "" if none is stored.
    func password() -> String {
        var query = item
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
}
