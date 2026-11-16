import Foundation
import Observation
import Shared

/// A local copy of both sites' account favorites (Favorites › All), kept
/// in the shared database by the shared module (see FavoritesMirror).
@MainActor
@Observable
final class FavoritesStore {
    static let shared = FavoritesStore()

    private(set) var items: [GalleryItem] = []
    private(set) var syncing: SiteKind?
    private(set) var syncedCount = 0
    private(set) var error: String?
    @ObservationIgnored private var lastSync: [SiteKind: Date] = [:]
    @ObservationIgnored private lazy var mirror = FavoritesMirror(
        db: IosDatabase.shared.shared, nh: NHClient.shared.api, eh: EhService.shared.api
    )

    /// The login each site's copy is a complete read of (the saved account's
    /// id, or `unidentified`), kept with the copy. Set only by a sync that read
    /// the whole list; none (an older version's copy, after a WebDAV import, a
    /// login that changed, a sync that failed part way): the copy is no one's
    /// and stays hidden.
    private var owners: [SiteKind: String] = [:]
    /// A login that isn't one of the saved accounts (from before accounts were kept).
    private static let unidentified = "?"
    @ObservationIgnored private var syncTask: Task<Void, Never>?

    private init() {
        for site in SiteKind.allCases {
            if let owner = UserDefaults.standard.string(forKey: "favoritesOwner.\(site.rawValue)") { owners[site] = owner }
        }
    }

    /// Who is logged in on `site`: the saved account's id, `unidentified`, or nil when no one.
    private func identity(_ site: SiteKind) -> String? {
        guard loggedIn(site) else { return nil }
        return AccountBook.shared.accounts(for: site).first { AccountBook.shared.isActive($0) }?.id ?? Self.unidentified
    }

    /// Whether `site`'s copy is the logged-in account's: a copy of no one (not
    /// yet read whole for this login) or of another account stays hidden, like
    /// a logged-out site's.
    private func showsCopy(_ site: SiteKind) -> Bool {
        guard let who = identity(site) else { return false }
        return owners[site] == who
    }

    private func setOwner(_ site: SiteKind, _ owner: String?) {
        owners[site] = owner
        UserDefaults.standard.set(owner, forKey: "favoritesOwner.\(site.rawValue)")
    }

    /// WebDAV added favorites from another device: they may be another
    /// account's, so the copy is no one's until it's read again from the site.
    func imported() async {
        for site in SiteKind.allCases { setOwner(site, nil) }
        await reload()
        await syncTask?.value
        await sync()
    }

    func reload() async {
        guard let all = try? await mirror.all() else { return }
        items = all.map(GalleryItem.init(favorite:))
    }

    /// Both sites' cards, newest first; [folders] limits E-Hentai's folders.
    func cards(folders: Set<Int>) -> [GalleryItem] {
        let shown = Set(SiteKind.allCases.filter(showsCopy))
        return items.filter {
            shown.contains($0.site) && ($0.site == .nhentai || folders.count >= 10 || folders.contains($0.favoriteFolder ?? 0))
        }
    }

    func added(_ card: GalleryItem, folder: Int) async {
        _ = try? await mirror.added(card: card.kotlinSummary, folder: Int32(folder))
        await reload()
    }

    func removed(_ card: GalleryItem) async {
        _ = try? await mirror.removed(key: card.kotlinKey)
        await reload()
    }

    private func loggedIn(_ site: SiteKind) -> Bool {
        site == .nhentai ? UserSession.shared.isLoggedIn : EhSession.shared.isLoggedIn
    }

    /// The login of `site` changed (another account, a new login, or none):
    /// a sync that is running was the previous account's and is waited for;
    /// then the new account's favorites are read.
    func accountChanged(_ site: SiteKind) async {
        lastSync[site] = nil
        // Hidden at once: nothing of the previous login shows until the new one's list is read.
        setOwner(site, nil)
        await syncTask?.value
        await sync([site])
    }

    /// Syncs the logged-in sites whose last sync is older than [maxAge].
    func syncIfStale(maxAge: TimeInterval = 15 * 60) async {
        let due = AppSettings.shared.enabledSites.filter {
            loggedIn($0) && Date().timeIntervalSince(lastSync[$0] ?? .distantPast) > maxAge
        }
        if !due.isEmpty { await sync(due) }
        else if items.isEmpty { await reload() }
    }

    func sync(_ sites: [SiteKind] = AppSettings.shared.enabledSites) async {
        guard syncing == nil else { return }
        let task = Task { await run(sites) }
        syncTask = task
        await task.value
    }

    private func run(_ sites: [SiteKind]) async {
        error = nil
        for site in sites where loggedIn(site) {
            syncing = site
            syncedCount = 0
            do {
                // A copy that isn't this login's (another account's, an older
                // version's, imported) is dropped before this login's is read.
                guard let who = identity(site) else { continue }
                if owners[site] != who {
                    setOwner(site, nil)
                    _ = try await mirror.clear(site: site.kotlin)
                    await reload()
                }
                EhService.shared.api.useExHentai = AppSettings.shared.useExHentai
                try await mirror.sync(site: site.kotlin, progress: nil)
                // The whole list was read for this login: one that changed meanwhile is read again after this.
                if identity(site) == who { setOwner(site, who) }
                lastSync[site] = Date()
            } catch {
                self.error = EhService.map(error).localizedDescription
            }
        }
        syncing = nil
        await reload()
    }
}

extension GalleryItem {
    /// A card from the favorites copy.
    init(favorite r: FavoriteRecord) {
        let site = SiteKind(rawValue: r.site) ?? .nhentai
        self.init(id: Int(r.galleryId), mediaID: r.mediaId, title: r.title, thumbnail: nil,
                  thumbnailWidth: Int(r.thumbWidth), thumbnailHeight: Int(r.thumbHeight), numPages: Int(r.numPages),
                  tagIDs: r.tagIds.split(separator: ",").compactMap { Int($0) })
        self.site = site
        token = r.token
        absoluteThumb = r.thumbnail
        favoriteFolder = Int(r.folder)
        rating = r.rating > 0 ? r.rating : nil
        restoreEh(language: r.language, category: r.category)
    }

    /// E-Hentai's language and category kept with history and favorites (cards).
    mutating func restoreEh(language: String, category: String) {
        guard site == .ehentai else { return }
        if !language.isEmpty { ehLanguage = Self.ehLanguageName(badge: language) }
        if let found = EhCategory.companion.fromLabel(label: category) { self.category = CategoryInfo(found) }
    }

    /// The E-Hentai language of a badge ("ZH" → "chinese"), the reverse of languageBadge.
    static func ehLanguageName(badge: String) -> String {
        let known = ["JP": "japanese", "ZH": "chinese", "EN": "english", "KO": "korean", "ES": "spanish",
                     "FR": "french", "DE": "german", "IT": "italian", "PT": "portuguese", "RU": "russian",
                     "VI": "vietnamese", "TH": "thai", "ID": "indonesian", "PL": "polish"]
        return known[badge] ?? badge.lowercased()
    }

    /// What history and favorites keep for an E-Hentai card: its badge and category.
    var ehKept: (language: String, category: String) {
        guard site == .ehentai else { return ("", "") }
        return (languageBadge ?? "", category?.label ?? "")
    }

    var kotlinKey: GalleryKey {
        Bridge.shared.galleryKey(site: site.kotlin, id: Int64(id), token: token)
    }

    /// The shared module's card (favorites copy).
    var kotlinSummary: GallerySummary {
        Bridge.shared.summary(site: site.kotlin, id: Int64(id), token: token, title: title,
                              thumbnail: thumbURL?.absoluteString, thumbWidth: Int32(thumbnailWidth),
                              thumbHeight: Int32(thumbnailHeight), pages: Int32(numPages), mediaId: mediaID,
                              tagIds: tagIDs.map { KotlinInt(int: Int32($0)) }, rating: rating ?? 0,
                              language: ehKept.language, category: ehKept.category)
    }
}
