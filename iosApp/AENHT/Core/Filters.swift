import Foundation
import Observation
import Shared

/// The user's filter rules (both sites, stored with the settings) and the
/// nhentai account's blacklist, applied to browsing lists.
@MainActor
@Observable
final class Filters {
    static let shared = Filters()

    private(set) var nhBlacklist: [Int] = []
    @ObservationIgnored private var lastBlacklistLoad = Date.distantPast

    var rules: [FilterRule] { Bridge.shared.filterRules(json: AppSettings.shared.filterRules) }

    private func save(_ rules: [FilterRule]) {
        AppSettings.shared.filterRules = Bridge.shared.encodeFilterRules(rules: rules)
    }

    func add(target: String, pattern: String, regex: Bool) {
        let trimmed = pattern.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        save(rules + [Bridge.shared.filterRule(id: UUID().uuidString, target: target, pattern: trimmed, regex: regex, enabled: true)])
    }

    func setEnabled(_ rule: FilterRule, _ enabled: Bool) {
        save(rules.map { $0.id == rule.id ? Bridge.shared.filterRule(id: $0.id, target: $0.target, pattern: $0.pattern, regex: $0.regex, enabled: enabled) : $0 })
    }

    func remove(_ rule: FilterRule) {
        save(rules.filter { $0.id != rule.id })
    }

    /// Whether a comment is hidden by a comment or commenter rule.
    func hidesComment(author: String, text: String) -> Bool {
        Bridge.shared.contentFilter(rules: rules, nhBlacklist: []).hidesComment(author: author, text: text)
    }

    /// Cards that no rule hides.
    func visible(_ items: [GalleryItem]) -> [GalleryItem] {
        let blacklist = AppSettings.shared.useNhBlacklist && UserSession.shared.isLoggedIn ? nhBlacklist : []
        let filter = Bridge.shared.contentFilter(rules: rules, nhBlacklist: blacklist.map { KotlinInt(int: Int32($0)) })
        guard !filter.isEmpty else { return items }
        let tagStore = TagStore.shared
        return items.filter { item in
            let names = item.site == .nhentai ? item.tagIDs.compactMap { tagStore.tag($0)?.name } : item.ehTags
            return !filter.hides(card: item.filterCard, tagNames: names)
        }
    }

    /// Loads the nhentai blacklist (at most every 30 minutes).
    func refreshNhBlacklist(force: Bool = false) async {
        guard UserSession.shared.isLoggedIn, AppSettings.shared.useNhBlacklist else { return }
        guard force || Date().timeIntervalSince(lastBlacklistLoad) > 30 * 60 else { return }
        lastBlacklistLoad = Date()
        if let ids = try? await NHClient.shared.api.blacklistIds() {
            nhBlacklist = ids.map { Int(truncating: $0) }
        }
    }
}

extension GalleryItem {
    /// The shared module's card with what the filter looks at.
    var filterCard: GallerySummary {
        Bridge.shared.filterCard(site: site.kotlin, title: title, uploader: uploader,
                                 tagIds: tagIDs.map { KotlinInt(int: Int32($0)) })
    }
}
