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

    private var revision: Int64 = 0

    func snapshot() -> TokenSnapshot {
        lock.withLock {
            let values = Keychain.nhCredentials.values()
            return TokenSnapshot(accessToken: values?[accessKey], refreshToken: values?[refreshKey], revision: revision)
        }
    }

    private func matches(_ expected: TokenSnapshot) -> Bool {
        let values = Keychain.nhCredentials.values()
        return revision == expected.revision && values?[accessKey] == expected.accessToken && values?[refreshKey] == expected.refreshToken
    }

    func renew(expected: TokenSnapshot, access: String, refresh: String?) -> Bool {
        let saved: Bool = lock.withLock {
            guard matches(expected) else { return false }
            var values: [(String, String?)] = [(accessKey, access)]
            if let refresh, !refresh.isEmpty { values.append((refreshKey, refresh)) }
            return Keychain.writeAll(values)
        }
        if saved, let previous = expected.accessToken, previous != access {
            // Use this renewal's tuple, never a different account switched in after the lock.
            let renewed = (refresh?.isEmpty == false) ? refresh : expected.refreshToken
            Task { @MainActor in AccountBook.shared.tokensRenewed(previousAccess: previous, access: access, refresh: renewed) }
        }
        return saved
    }

    func replace(expected: TokenSnapshot, access: String, refresh: String?) -> Bool {
        lock.withLock {
            guard matches(expected), Keychain.writeAll([(accessKey, access), (refreshKey, refresh)]) else { return false }
            revision += 1
            return true
        }
    }

    func replace(access: String, refresh: String?) -> Bool {
        lock.withLock {
            guard Keychain.writeAll([(accessKey, access), (refreshKey, refresh)]) else { return false }
            revision += 1
            return true
        }
    }

    @discardableResult
    func clearLogin() -> Bool {
        lock.withLock {
            // Invalidate pending work even when the device refuses the logout.
            revision += 1
            return Keychain.deleteAll([accessKey, refreshKey])
        }
    }

    func clear() { _ = clearLogin() }

}

enum Keychain {
    static let service = "com.atlanyuey.aenht"

    static let nhCredentials = AtomicCredentials(
        record: "nh_credentials_v1", legacyKeys: ["api_v2_access_token", "api_v2_refresh_token"],
        read: readValue, write: writeRaw, remove: deleteRaw
    )
    private static let ehCredentials = AtomicCredentials(
        record: "eh_credentials_v1", legacyKeys: ["eh_member_id", "eh_pass_hash", "eh_igneous"],
        read: readValue, write: writeRaw, remove: deleteRaw
    )

    private static func group(_ key: String) -> AtomicCredentials? {
        switch key {
        case "api_v2_access_token", "api_v2_refresh_token": return nhCredentials
        case "eh_member_id", "eh_pass_hash", "eh_igneous": return ehCredentials
        default: return nil
        }
    }

    static func read(_ key: String) -> String? {
        if let group = group(key) { return group.values()?[key] }
        if case let .value(value) = readValue(key), !value.isEmpty { return value }
        return nil
    }

    private static func readValue(_ key: String) -> SecretRead {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else { return .unavailable }
        return .value(value)
    }

    /// Saves a value: the item there is is updated in place (what was saved
    /// stays when the Keychain refuses), or a new one is added.
    @discardableResult
    static func write(_ key: String, _ value: String) -> Bool {
        if let group = group(key) { return group.update([(key, value)]) }
        return writeRaw(key, value)
    }

    private static func writeRaw(_ key: String, _ value: String) -> Bool {
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

    /// Site credentials are committed as one Keychain item, including legacy migration.
    static func writeAll(_ values: [(String, String?)]) -> Bool {
        guard let key = values.first?.0, let group = group(key),
              values.allSatisfy({ self.group($0.0) === group }) else { return false }
        return group.update(values)
    }

    /// Removes a value; false when the Keychain refused (one that isn't there is removed).
    @discardableResult
    static func delete(_ key: String) -> Bool {
        if let group = group(key) { return group.update([(key, nil)]) }
        return deleteRaw(key)
    }

    private static func deleteRaw(_ key: String) -> Bool {
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
        if let first = keys.first, let group = group(first), keys.allSatisfy({ self.group($0) === group }) {
            return group.update(keys.map { ($0, nil) })
        }
        return keys.map { delete($0) }.allSatisfy { $0 }
    }
}

/// The signed-in user, observed by the UI.
@Observable
final class UserSession {
    static let shared = UserSession()

    var storageError: String?
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
    @discardableResult
    func logout() -> Bool {
        let active = AccountBook.shared.accounts(for: .nhentai).first { AccountBook.shared.isActive($0) }
        guard AuthStore.shared.clearLogin() else {
            storageError = String(localized: "The logout couldn't be saved. Your account is still signed in.")
            return false
        }
        generation += 1
        if let active { AccountBook.shared.forget(active) }
        user = nil
        isLoggedIn = false
        Task { @MainActor in
            await WebLogins.forget(WebLogins.nhentai)
            await accountChanged()
        }
        return true
    }
}
