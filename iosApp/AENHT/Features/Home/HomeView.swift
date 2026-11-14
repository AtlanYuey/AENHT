import Shared
import SwiftUI

struct HomeView: View {
    @Environment(AppSettings.self) private var settings
    /// nhentai's random gallery, opened from the title's menu.
    @State private var randomRoute: GalleryRoute?
    /// The rows pinned above the feed (Settings › General › Pinned Rows), as loaded.
    @State private var rows: [HomeRow: [GalleryItem]] = [:]
    @State private var nhFeed = GalleryFeed.nh { page in
        let settings = AppSettings.shared
        return try await NHClient.shared.galleries(
            query: settings.homeLanguage.queryTerm ?? "",
            sort: settings.homeSort,
            page: page
        )
    }
    @State private var ehFeed = GalleryFeed.eh {
        AppSettings.shared.ehHomeList.source(language: AppSettings.shared.homeLanguage)
    }

    private var feed: GalleryFeed { settings.currentSite == .ehentai ? ehFeed : nhFeed }
    /// The search shows over the feed.
    @State private var searching = false
    /// The feed's header (sort, language, search) has scrolled under the
    /// navigation bar: its buttons show in the bar instead.
    @State private var docked = false
    /// Bottom of the feed's header in the feed's coordinates.
    @State private var headerBottom: CGFloat = .infinity

    private static let space = "homeFeed"

    @State private var scroll = ScrollPosition(edge: .top)
    /// How far the feed is scrolled (0 at the top).
    @State private var offset: CGFloat = 0
    /// Where the feed was when the Home tab took it to the top.
    @State private var savedOffset: CGFloat?
    /// A tap on the Home tab at the top, waiting to see if a second follows.
    @State private var pendingTap: Task<Void, Never>?
    @State private var lastTopTap = Date.distantPast
    @State private var visible = false
    @State private var chrome = AppChrome.shared

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(settings.homeRows(for: settings.currentSite)) { row in
                    if let items = rows[row], !items.isEmpty {
                        SectionTitle(title: LocalizedStringKey(row.titleKey))
                        PopularRow(items: items)
                    }
                }
                FeedHeader { startSearch() }
                    .onGeometryChange(for: CGFloat.self) {
                        $0.frame(in: .named(Self.space)).maxY
                    } action: { headerBottom = $0 }
                GalleryCollection(items: feed.items) { item in
                    Task { await feed.loadMoreIfNeeded(after: item) }
                }
                FeedPlaceholder(feed: feed)
                FeedFooter(feed: feed)
            }
            .coordinateSpace(.named(Self.space))
        }
        .scrollPosition($scroll)
        .onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top
        } action: { _, new in
            offset = new
        }
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .onChange(of: chrome.homeTabTaps) { homeTabTapped() }
        .onScrollGeometryChange(for: Bool.self) { geometry in
            // The bar's bottom edge, in the feed's coordinates, is past the header.
            geometry.contentOffset.y + geometry.contentInsets.top > headerBottom - 4
        } action: { _, under in
            withAnimation(.smooth(duration: 0.25)) { docked = under }
        }
        .hidesBarsOnScroll()
        // The system bar (with its soft edge over the content): the site's
        // name in the middle (a menu that switches the site). The feed's
        // header (sort, language, search) sits above the feed and moves into
        // the bar once it has scrolled under it.
        .navigationTitle(settings.currentSite.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarTitleMenu {
            if settings.enabledSites.count > 1 {
                Picker("Site", selection: Binding(get: { settings.currentSite }, set: { settings.homeSite = $0 })) {
                    ForEach(settings.enabledSites) { Text($0.displayName).tag($0) }
                }
            }
            if settings.currentSite == .nhentai {
                Button("Random Gallery", systemImage: "dice") {
                    Task {
                        if let id = try? await NHClient.shared.randomID() { randomRoute = GalleryRoute(id: id, preview: nil) }
                    }
                }
            }
        }
        .navigationDestination(item: $randomRoute) { GalleryDetailView(route: $0) }
        .toolbar(searching ? .hidden : .automatic, for: .navigationBar)
        .toolbar {
            if docked {
                ToolbarItem(placement: .topBarLeading) {
                    SortMenu(compact: true)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    LanguageMenu(compact: true)
                    Button {
                        startSearch()
                    } label: {
                        Label("Search", systemImage: "magnifyingglass")
                    }
                }
            }
        }
        .overlay {
            if searching {
                InlineSearch(isActive: $searching)
                    .transition(.opacity)
            }
        }
        .refreshable { await reload() }
        .task {
            if !feed.hasLoaded { await reload() }
        }
        .onChange(of: settings.homeLanguage) { Task { await feed.reload() } }
        .onChange(of: settings.homeSort) { Task { await nhFeed.reload() } }
        .onChange(of: settings.ehHomeList) { Task { await ehFeed.reload() } }
        .onChange(of: settings.useExHentai) {
            // ExHentai's lists are other galleries.
            rows = rows.filter { $0.key.site != .ehentai }
            Task {
                async let rows: Void = loadRows()
                await ehFeed.reload()
                await rows
            }
        }
        .onChange(of: settings.homeRowsRaw) { Task { await loadRows() } }
        .onChange(of: settings.currentSite) { Task { if !feed.hasLoaded { await reload() } } }
    }

    /// The Home tab was tapped while Home shows: away from the top, the feed
    /// goes to the top; at the top, back to where it was; tapped twice
    /// quickly, Home refreshes.
    private func homeTabTapped() {
        guard visible, !searching else { return }
        let doubleTap: TimeInterval = 0.3
        if pendingTap != nil || Date().timeIntervalSince(lastTopTap) < doubleTap {
            pendingTap?.cancel()
            pendingTap = nil
            lastTopTap = .distantPast
            savedOffset = nil
            withAnimation(.smooth) { scroll.scrollTo(edge: .top) }
            Task { await reload() }
            return
        }
        if offset > 1 {
            savedOffset = offset
            lastTopTap = Date()
            withAnimation(.smooth) { scroll.scrollTo(edge: .top) }
            return
        }
        pendingTap = Task {
            try? await Task.sleep(for: .seconds(doubleTap))
            guard !Task.isCancelled else { return }
            pendingTap = nil
            guard let saved = savedOffset else { return }
            savedOffset = nil
            withAnimation(.smooth) { scroll.scrollTo(y: saved) }
        }
    }

    private func startSearch() {
        withAnimation(.smooth(duration: 0.25)) { searching = true }
    }

    private func reload() async {
        let current = feed
        async let list: Void = current.reload()
        await loadRows()
        await list
    }

    private func loadRows() async {
        let wanted = settings.homeRows(for: settings.currentSite)
        await withTaskGroup(of: (HomeRow, [GalleryItem]?).self) { group in
            for row in wanted {
                group.addTask { (row, try? await Self.load(row)) }
            }
            for await (row, items) in group {
                if let items { rows[row] = items }
            }
        }
    }

    private static func load(_ row: HomeRow) async throws -> [GalleryItem] {
        switch row {
        case .nhPopular: return try await NHClient.shared.popular()
        case .nhToday: return try await NHClient.shared.galleries(query: "", sort: .popularToday, page: 1).result
        case .nhWeek: return try await NHClient.shared.galleries(query: "", sort: .popularWeek, page: 1).result
        case .nhMonth: return try await NHClient.shared.galleries(query: "", sort: .popularMonth, page: 1).result
        case .nhAll: return try await NHClient.shared.galleries(query: "", sort: .popular, page: 1).result
        case .ehPopular: return try await EhService.shared.list(Bridge.shared.popular(), cursor: nil).items
        case .ehDay: return try await EhService.shared.list(Bridge.shared.toplist(period: 0), cursor: nil).items
        case .ehMonth: return try await EhService.shared.list(Bridge.shared.toplist(period: 1), cursor: nil).items
        case .ehYear: return try await EhService.shared.list(Bridge.shared.toplist(period: 2), cursor: nil).items
        case .ehAll: return try await EhService.shared.list(Bridge.shared.toplist(period: 3), cursor: nil).items
        }
    }
}

/// The feed's header: the sort (a button that switches it, with the
/// language under it), and on the right the language and search buttons.
/// Scrolled under the navigation bar, the same buttons show in the bar.
private struct FeedHeader: View {
    let onSearch: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            SortMenu(compact: false)
            Spacer(minLength: 0)
            LanguageMenu(compact: false)
            CircleButton(systemImage: "magnifyingglass", label: "Search", tint: .primary, action: onSearch)
        }
        .padding(.horizontal)
        .padding(.top, 20)
        .padding(.bottom, 10)
    }
}

/// Switches the Home feed's sort: the section title, or a small button in
/// the navigation bar.
private struct SortMenu: View {
    let compact: Bool

    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        let title = settings.currentSite == .ehentai ? settings.ehHomeList.title : settings.homeSort.title
        Menu {
            if settings.currentSite == .ehentai {
                Picker("Lists", selection: $settings.ehHomeList) {
                    ForEach(EhHomeList.allCases.prefix(3)) { Text($0.title).tag($0) }
                }
                Picker("Toplists", selection: $settings.ehHomeList) {
                    ForEach(EhHomeList.allCases.dropFirst(3)) { Text($0.title).tag($0) }
                }
            } else {
                Picker("Sort", selection: $settings.homeSort) {
                    ForEach(SearchSort.allCases) { Text($0.title).tag($0) }
                }
            }
        } label: {
            if compact {
                HStack(spacing: 4) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.bold))
                }
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(title)
                            .font(.title2.bold())
                            .foregroundStyle(.primary)
                        Image(systemName: "chevron.down")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                    Text(settings.homeLanguage.title)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Sort"))
    }
}

/// The Home feed's language: a round button in the header, a bar button
/// when docked.
private struct LanguageMenu: View {
    let compact: Bool

    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        Menu {
            Picker("Language", selection: $settings.homeLanguage) {
                ForEach(LanguageFilter.allCases) { Text($0.title).tag($0) }
            }
        } label: {
            if compact {
                Label("Language", systemImage: "globe")
            } else {
                Image(systemName: "globe")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
        }
        .accessibilityLabel(Text("Language"))
        .modifier(OptionalCircleGlass(enabled: !compact))
    }
}

private struct OptionalCircleGlass: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.modifier(CircleGlassStyle())
        } else {
            content
        }
    }
}

/// Section heading in the style of the App Store / Music.
struct SectionTitle: View {
    let title: LocalizedStringKey
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title2.bold())
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
        .padding(.top, 20)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Horizontally scrolling row of covers.
struct PopularRow: View {
    let items: [GalleryItem]

    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items) { item in
                    GalleryCard(item: item, style: .grid)
                        .frame(width: sizeClass == .regular ? 170 : 132)
                }
            }
            .scrollTargetLayout()
            .padding(.horizontal)
        }
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(.viewAligned)
    }
}
