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
        let previous = accessToken
        Keychain.write(accessKey, access)
        if let refresh, !refresh.isEmpty {
            Keychain.write(refreshKey, refresh)
        }
        // A renewed login: the saved account gets the new tokens (it stays the active one).
        if let previous, previous != access {
            let renewed = refreshToken
            Task { @MainActor in AccountBook.shared.tokensRenewed(previousAccess: previous, access: access, refresh: renewed) }
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

    /// Saves a value: the item there is is updated in place (what was saved
    /// stays when the Keychain refuses), or a new one is added.
    @discardableResult
    static func write(_ key: String, _ value: String) -> Bool {
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let data = Data(value.utf8)
        var status = SecItemUpdate(item as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var added = item
            added[kSecValueData as String] = data
            added[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = SecItemAdd(added as CFDictionary, nil)
        }
        if status != errSecSuccess {
            Diagnostics.shared.log(category: "keychain", message: "Couldn't save \"\(key)\" (\(status))")
        }
        return status == errSecSuccess
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
            AccountBook.shared.rememberNh(user: user)
        }
    }

    /// Logs in with an API key (nhentai.net › Settings › API Keys).
    @MainActor
    func loginWithApiKey(_ key: String) async throws {
        try await NHClient.shared.loginWithApiKey(key)
        user = nil
        await reload()
        await accountChanged()
    }

    /// What belonged to the previous login (favorites copy, blacklist) follows the new one.
    @MainActor
    private func accountChanged() async {
        Filters.shared.accountChanged()
        await FavoritesStore.shared.accountChanged(.nhentai)
    }

    @MainActor
    func login(access: String, refresh: String?) async {
        // Nothing of the login before stays: not its refresh token, not its user.
        AuthStore.shared.clear()
        user = nil
        AuthStore.shared.save(access: access, refresh: refresh)
        await reload()
        await accountChanged()
    }

    @MainActor
    func logout() {
        AccountBook.shared.forgetActive(.nhentai)
        AuthStore.shared.clear()
        user = nil
        isLoggedIn = false
        Task { @MainActor in await accountChanged() }
    }
}
