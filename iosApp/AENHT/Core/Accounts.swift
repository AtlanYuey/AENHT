import Foundation
import Observation

/// A saved login of a site; `values` are its credentials (the list lives in the Keychain).
struct SavedAccount: Codable, Identifiable, Hashable {
    let id: String
    let site: String
    var name: String
    var avatar: String?
    var values: [String: String]

    var siteKind: SiteKind { SiteKind(rawValue: site) ?? .nhentai }
}

/// Several accounts per site: each login is saved here, and switching puts
/// an account's credentials back as the active login.
@MainActor
@Observable
final class AccountBook {
    static let shared = AccountBook()

    private(set) var accounts: [SavedAccount] = []

    private init() {
        if let text = Keychain.read("saved_accounts"), let data = text.data(using: .utf8),
           let list = try? JSONDecoder().decode([SavedAccount].self, from: data) {
            accounts = list
        }
    }

    private func save(_ list: [SavedAccount]) {
        accounts = list
        if let data = try? JSONEncoder().encode(list), let text = String(data: data, encoding: .utf8) {
            Keychain.write("saved_accounts", text)
        }
    }

    func accounts(for site: SiteKind) -> [SavedAccount] { accounts.filter { $0.siteKind == site } }

    func isActive(_ account: SavedAccount) -> Bool {
        account.siteKind == .nhentai
            ? account.values["access"] == AuthStore.shared.accessToken
            : account.values["member"] == EhCredentials.shared.memberId
    }

    /// The nhentai login was renewed: its saved account keeps up, or switching back to it would use dead tokens.
    func tokensRenewed(previousAccess: String, access: String, refresh: String?) {
        guard accounts.contains(where: { $0.siteKind == .nhentai && $0.values["access"] == previousAccess }) else { return }
        save(accounts.map { account in
            guard account.siteKind == .nhentai, account.values["access"] == previousAccess else { return account }
            var renewed = account
            renewed.values = ["access": access]
            if let refresh { renewed.values["refresh"] = refresh }
            return renewed
        })
    }

    private func upsert(_ account: SavedAccount) {
        save(accounts.filter { $0.id != account.id } + [account])
    }

    /// Saves the active nhentai login (after logging in or reloading the user).
    func rememberNh(user: CurrentUser?) {
        guard let access = AuthStore.shared.accessToken, !access.isEmpty else { return }
        var values = ["access": access]
        if let refresh = AuthStore.shared.refreshToken { values["refresh"] = refresh }
        let id = "nh:" + (user.map { String($0.id) } ?? String(access.hashValue))
        upsert(SavedAccount(id: id, site: SiteKind.nhentai.rawValue, name: user?.username ?? "nhentai",
                            avatar: user?.avatar?.absoluteString, values: values))
    }

    /// Saves the active E-Hentai login.
    func rememberEh() {
        let c = EhCredentials.shared
        guard let member = c.memberId, let hash = c.passHash else { return }
        var values = ["member": member, "hash": hash]
        if let igneous = c.igneous { values["igneous"] = igneous }
        upsert(SavedAccount(id: "eh:\(member)", site: SiteKind.ehentai.rawValue, name: "ID \(member)", avatar: nil, values: values))
    }

    /// Makes `account` the active login of its site.
    func switchTo(_ account: SavedAccount) async {
        switch account.siteKind {
        case .nhentai:
            guard let access = account.values["access"] else { return }
            AuthStore.shared.clear()
            await UserSession.shared.login(access: access, refresh: account.values["refresh"])
        case .ehentai:
            guard let member = account.values["member"], let hash = account.values["hash"] else { return }
            EhCredentials.shared.save(memberId: member, passHash: hash, igneous: account.values["igneous"])
            EhSession.shared.refresh()
            EhSession.shared.resetFolders()
            await EhSession.shared.loadFolders()
        }
        // nhentai's login above already told the favorites copy.
        if account.siteKind == .ehentai { await FavoritesStore.shared.accountChanged(.ehentai) }
    }

    func forget(_ account: SavedAccount) { save(accounts.filter { $0.id != account.id }) }

    /// Forgets the active account of `site` (logging out).
    func forgetActive(_ site: SiteKind) { save(accounts.filter { !($0.siteKind == site && isActive($0)) }) }
}
