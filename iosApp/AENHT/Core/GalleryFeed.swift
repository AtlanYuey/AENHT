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

    @ObservationIgnored private var next: String?
    @ObservationIgnored private var seen = Set<String>()
    @ObservationIgnored private var generation = 0
    @ObservationIgnored var fetch: Fetch

    init(fetch: @escaping Fetch) {
        self.fetch = fetch
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
        generation += 1
        let current = generation
        isLoading = true
        error = nil
        do {
            let response = try await fetch(nil)
            guard current == generation else { return }
            next = response.next
            total = response.total
            seen = []
            items = unique(response.items)
        } catch is CancellationError {
        } catch {
            guard current == generation else { return }
            self.error = error.localizedDescription
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
        do {
            let response = try await fetch(cursor)
            guard current == generation else { return }
            next = response.next
            items.append(contentsOf: unique(response.items))
        } catch {
            guard current == generation else { return }
            self.error = error.localizedDescription
        }
        if current == generation {
            isLoading = false
        }
    }

    private func unique(_ list: [GalleryItem]) -> [GalleryItem] {
        list.filter { seen.insert($0.uid).inserted }
    }
}
