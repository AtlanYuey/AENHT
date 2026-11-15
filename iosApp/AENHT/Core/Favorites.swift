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

    private init() {}

    func reload() async {
        guard let all = try? await mirror.all() else { return }
        items = all.map(GalleryItem.init(favorite:))
    }

    /// Both sites' cards, newest first; [folders] limits E-Hentai's folders.
    func cards(folders: Set<Int>) -> [GalleryItem] {
        items.filter { $0.site == .nhentai || folders.count >= 10 || folders.contains($0.favoriteFolder ?? 0) }
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

    /// Syncs the logged-in sites whose last sync is older than [maxAge].
    /// Another account of `site` became active: its copy is read again.
    func accountChanged(_ site: SiteKind) async {
        lastSync[site] = nil
        await sync([site])
    }

    func syncIfStale(maxAge: TimeInterval = 15 * 60) async {
        let due = AppSettings.shared.enabledSites.filter {
            loggedIn($0) && Date().timeIntervalSince(lastSync[$0] ?? .distantPast) > maxAge
        }
        if !due.isEmpty { await sync(due) }
        else if items.isEmpty { await reload() }
    }

    func sync(_ sites: [SiteKind] = AppSettings.shared.enabledSites) async {
        guard syncing == nil else { return }
        error = nil
        for site in sites where loggedIn(site) {
            syncing = site
            syncedCount = 0
            do {
                EhService.shared.api.useExHentai = AppSettings.shared.useExHentai
                try await mirror.sync(site: site.kotlin, progress: nil)
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
