import Foundation
import Shared
import Observation

/// An endless list of galleries of either site, loaded page by page, with
/// pull-to-refresh.
@MainActor
@Observable
final class GalleryFeed {
    /// Loads the page after [cursor] (nil: the first page).
    typealias Fetch = (String?) async throws -> FeedPage

    private(set) var items: [GalleryItem] = []
    private(set) var isLoading = false
    private(set) var error: String?
    private(set) var hasLoaded = false
    private(set) var total: Int?
    /// The filters hid whole pages in a row and more remain: the list offers to keep looking.
    private(set) var skippedHidden = false

    @ObservationIgnored private var next: String?
    @ObservationIgnored private var seen = Set<String>()
    /// Everything loaded, before the filters (shown again when the rules change).
    @ObservationIgnored private var loaded: [GalleryItem] = []
    @ObservationIgnored private var filterVersion = 0
    /// A hidden page doesn't end the list: this many more are read (each is a request to the site).
    private static let maxHiddenPages = 4
    @ObservationIgnored private var generation = 0
    @ObservationIgnored var fetch: Fetch
    /// Browsing lists leave out galleries hidden by the filter rules (not favorites).
    @ObservationIgnored var filtered = true

    init(fetch: @escaping Fetch) {
        self.fetch = fetch
        watchFilters()
        watchConnectivity()
    }

    /// A page that failed (offline) is loaded again when the network comes back.
    private func watchConnectivity() {
        withObservationTracking {
            _ = Connectivity.shared.restored
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.watchConnectivity()
                guard self.error != nil, !self.isLoading else { return }
                if self.items.isEmpty { await self.reload() } else { await self.loadMore() }
            }
        }
    }

    /// The rules, the account's blacklist or the tag names changed: what is
    /// loaded is filtered again (whether or not the end of the list shows).
    private func watchFilters() {
        withObservationTracking {
            _ = Filters.shared.version
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.refilter()
                self?.watchFilters()
            }
        }
    }

    /// A feed of nhentai pages (page numbers as cursors); blacklisted galleries are left out.
    static func nh(_ fetch: @escaping (Int) async throws -> PageResponse<GalleryItem>) -> GalleryFeed {
        GalleryFeed { cursor in
            let page = Int(cursor ?? "1") ?? 1
            let response = try await fetch(page)
            return FeedPage(items: response.result.filter { !$0.blacklisted },
                            next: page < response.numPages ? "\(page + 1)" : nil,
                            total: response.total)
        }
    }

    /// A feed of an E-Hentai list.
    static func eh(_ source: @escaping @MainActor () -> EhListSource) -> GalleryFeed {
        GalleryFeed { cursor in
            let s = await source()
            return try await EhService.shared.list(s, cursor: cursor)
        }
    }

    var canLoadMore: Bool { hasLoaded && next != nil }

    func reload() async {
        if filtered { await Filters.shared.refreshNhBlacklist() }
        generation += 1
        let current = generation
        isLoading = true
        error = nil
        do {
            let response = try await fetch(nil)
            guard current == generation else { return }
            total = response.total
            seen = []
            loaded = []
            items = []
            try await take(response, current)
        } catch is CancellationError {
        } catch {
            guard current == generation else { return }
            self.error = ErrorText.of(error)
        }
        if current == generation {
            isLoading = false
            hasLoaded = true
        }
    }

    func loadMoreIfNeeded(after item: GalleryItem) async {
        guard canLoadMore, !isLoading else { return }
        guard let index = items.firstIndex(where: { $0.uid == item.uid }), index >= items.count - 8 else { return }
        await loadMore()
    }

    func loadMore() async {
        guard canLoadMore, !isLoading, let cursor = next else { return }
        let current = generation
        isLoading = true
        error = nil
        do {
            let response = try await fetch(cursor)
            guard current == generation else { return }
            try await take(response, current)
        } catch is CancellationError {
        } catch {
            guard current == generation else { return }
            self.error = ErrorText.of(error)
        }
        if current == generation {
            isLoading = false
        }
    }

    /// Adds a page. When the filters hide all of it, the next pages are read
    /// too, a few at most: a hidden page doesn't end the list.
    private func take(_ first: FeedPage, _ current: Int) async throws {
        var page = first
        var skipped = 0
        while true {
            next = page.next
            let fresh = page.items.filter { seen.insert($0.uid).inserted }
            loaded += fresh
            filterVersion = Filters.shared.version
            let visible = filtered ? Filters.shared.visible(fresh) : fresh
            items += visible
            let hidden = !fresh.isEmpty && visible.isEmpty && next != nil
            guard hidden, skipped < Self.maxHiddenPages, let cursor = next else {
                skippedHidden = hidden
                return
            }
            skipped += 1
            page = try await fetch(cursor)
            guard current == generation else { return }
        }
    }

    /// Applies the filters again to what is loaded (the rules or the account's blacklist changed).
    func refilter() {
        guard filtered, filterVersion != Filters.shared.version else { return }
        filterVersion = Filters.shared.version
        let visible = Filters.shared.visible(loaded)
        if visible.map(\.uid) != items.map(\.uid) { items = visible }
    }
}
