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

    /// Read, compare and write in one step (the shared module renews from its own threads).
    private let lock = NSLock()

    func renew(previousAccess: String, access: String, refresh: String?) -> Bool {
        let saved: Bool = lock.withLock {
            guard accessToken == previousAccess else { return false }
            var values: [(String, String?)] = [(accessKey, access)]
            if let refresh, !refresh.isEmpty { values.append((refreshKey, refresh)) }
            return Keychain.writeAll(values)
        }
        // The saved account gets the new tokens (it stays the active one).
        if saved, previousAccess != access {
            let renewed = refreshToken
            Task { @MainActor in AccountBook.shared.tokensRenewed(previousAccess: previousAccess, access: access, refresh: renewed) }
        }
        return saved
    }

    func replace(expected: String?, access: String, refresh: String?) -> Bool {
        lock.withLock {
            guard accessToken == expected else { return false }
            return Keychain.writeAll([(accessKey, access), (refreshKey, refresh)])
        }
    }

    /// Puts a new login in place of the one there is (both tokens); false
    /// when it couldn't be saved, and the old one stays.
    func replace(access: String, refresh: String?) -> Bool {
        lock.withLock { Keychain.writeAll([(accessKey, access), (refreshKey, refresh)]) }
    }

    func clear() {
        lock.withLock { Keychain.deleteAll([accessKey, refreshKey]) }
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

    /// Writes several values (nil or empty removes one). The Keychain has no
    /// transactions: when one write or removal is refused, the values there
    /// were are put back; when even that fails, all of them are removed, so a
    /// mix of two logins is never left (false in both cases).
    static func writeAll(_ values: [(String, String?)]) -> Bool {
        let before = values.map { ($0.0, read($0.0)) }
        for (key, value) in values {
            let done: Bool
            if let value, !value.isEmpty { done = write(key, value) } else { done = delete(key) }
            if done { continue }
            let restored = before.allSatisfy { key, old in
                if let old { write(key, old) } else { delete(key) }
            }
            if !restored {
                Diagnostics.shared.log(category: "keychain", message: "Couldn't restore \(values.map(\.0)): removed")
                deleteAll(values.map(\.0))
            }
            return false
        }
        return true
    }

    /// Removes a value; false when the Keychain refused (one that isn't there is removed).
    @discardableResult
    static func delete(_ key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess, status != errSecItemNotFound {
            Diagnostics.shared.log(category: "keychain", message: "Couldn't remove \"\(key)\" (\(status))")
            return false
        }
        return true
    }

    /// Removes several values (all those it can).
    @discardableResult
    static func deleteAll(_ keys: [String]) -> Bool {
        keys.map { delete($0) }.allSatisfy { $0 }
    }
}

/// The signed-in user, observed by the UI.
@Observable
final class UserSession {
    static let shared = UserSession()

    private(set) var user: CurrentUser?
    private(set) var isLoggedIn: Bool = AuthStore.shared.isLoggedIn

    /// Counts the changes of login (log in, log out, switch): an answer to a
    /// request of an earlier login (its user, its blacklist) isn't kept.
    @ObservationIgnored private(set) var generation = 0

    @MainActor
    func reload() async {
        isLoggedIn = AuthStore.shared.isLoggedIn
        guard isLoggedIn else {
            user = nil
            return
        }
        let mine = generation
        let token = AuthStore.shared.accessToken
        if let user = try? await NHClient.shared.currentUser() {
            // Another login meanwhile: this user isn't its user.
            guard mine == generation, AuthStore.shared.accessToken == token else { return }
            self.user = user
            AccountBook.shared.rememberNh(user: user)
        }
    }

    /// Logs in with an API key (nhentai.net › Settings › API Keys).
    @MainActor
    func loginWithApiKey(_ key: String) async throws {
        generation += 1
        let mine = generation
        // Checked on its own and saved only if the login is still the one there was.
        do {
            try await NHClient.shared.loginWithApiKey(key)
        } catch NHError.loginChanged {
            return // Logged out or into another account meanwhile: that one stays.
        }
        guard mine == generation else { return }
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

    /// Logs in with tokens; false when they couldn't be saved on this device (the login there was stays).
    @MainActor
    @discardableResult
    func login(access: String, refresh: String?) async -> Bool {
        // Nothing of the login before stays: not its refresh token, not its user.
        guard AuthStore.shared.replace(access: access, refresh: refresh) else { return false }
        generation += 1
        user = nil
        await reload()
        await accountChanged()
        return true
    }

    @MainActor
    func logout() {
        generation += 1
        AccountBook.shared.forgetActive(.nhentai)
        AuthStore.shared.clear()
        user = nil
        isLoggedIn = false
        Task { @MainActor in await accountChanged() }
    }
}
