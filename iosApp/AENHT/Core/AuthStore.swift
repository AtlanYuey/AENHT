import Foundation
import Shared
import Observation
import Security

/// Stores the API tokens in the Keychain. The shared Kotlin client reads
/// them through `TokenStorage` and saves renewed ones here.
final class AuthStore: NSObject, TokenStorage, @unchecked Sendable {
    static let shared = AuthStore()

    private let accessKey = "api_v2_access_token"
    private let refreshKey = "api_v2_refresh_token"

    var accessToken: String? { Keychain.read(accessKey) }
    var refreshToken: String? { Keychain.read(refreshKey) }
    var isLoggedIn: Bool { !(accessToken ?? "").isEmpty }

    func save(access: String, refresh: String?) {
        Keychain.write(accessKey, access)
        if let refresh, !refresh.isEmpty {
            Keychain.write(refreshKey, refresh)
        }
    }

    func clear() {
        Keychain.delete(accessKey)
        Keychain.delete(refreshKey)
    }
}

enum Keychain {
    static let service = "com.atlanyuey.aenht"

    static func read(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8),
              !value.isEmpty
        else { return nil }
        return value
    }

    static func write(_ key: String, _ value: String) {
        delete(key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// The signed-in user, observed by the UI.
@Observable
final class UserSession {
    static let shared = UserSession()

    private(set) var user: CurrentUser?
    private(set) var isLoggedIn: Bool = AuthStore.shared.isLoggedIn

    @MainActor
    func reload() async {
        isLoggedIn = AuthStore.shared.isLoggedIn
        guard isLoggedIn else {
            user = nil
            return
        }
        if let user = try? await NHClient.shared.currentUser() {
            self.user = user
        }
    }

    @MainActor
    func login(access: String, refresh: String?) async {
        AuthStore.shared.save(access: access, refresh: refresh)
        await reload()
    }

    @MainActor
    func logout() {
        AuthStore.shared.clear()
        user = nil
        isLoggedIn = false
    }
}
