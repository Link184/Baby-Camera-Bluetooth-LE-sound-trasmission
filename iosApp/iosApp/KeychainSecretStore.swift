import Foundation
import Security
import Shared

/// The shared code's `SecretStore` on iOS: the pairing keys live in the Keychain, readable after the
/// first unlock (so the monitor works with the screen locked) and never synced or backed up to
/// another device.
final class KeychainSecretStore: NSObject, SecretStore {
    private let service = "com.link184.babycamera.secrets"

    func read(name: String) -> String? {
        var query = baseQuery(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func write(name: String, value: String?) {
        let query = baseQuery(name)
        SecItemDelete(query as CFDictionary)
        guard let value else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    private func baseQuery(_ name: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
        ]
    }
}
