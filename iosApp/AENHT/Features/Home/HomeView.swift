import Shared
import SwiftUI

struct HomeView: View {
    @Environment(AppSettings.self) private var settings
    /// nhentai's random gallery, opened from the die at the top right.
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

    /// Wide (a tablet held sideways): the two pinned rows show side by side.
    @State private var wide = false

    private struct PinnedRow: Identifiable {
        let row: HomeRow
        let items: [GalleryItem]
        var id: String { row.rawValue }
    }

    /// The pinned rows that have something to show; they follow the filter rules like the feed under them.
    private var pinnedRows: [PinnedRow] {
        settings.homeRows(for: settings.currentSite).compactMap { row in
            guard let items = rows[row] else { return nil }
            let visible = Filters.shared.visible(items)
            return visible.isEmpty ? nil : PinnedRow(row: row, items: visible)
        }
    }

    var body: some View {
        GalleryList(
            blocks: [
                // The pinned rows and the feed's header (its bottom is where the header docks in the bar).
                .view("header") { header },
                .cards("feed", feed.items),
                .view("footer") {
                    VStack(spacing: 0) {
                        FeedPlaceholder(feed: feed)
                        FeedFooter(feed: feed)
                    }
                },
            ],
            onItemAppear: { item in Task { await feed.loadMoreIfNeeded(after: item) } },
            refresh: { await reload() },
            tab: .home,
            tabTapEnabled: !searching,
            onScroll: { offset, headerBottom in
                // The bar's bottom edge, in the feed's coordinates, is past the header.
                let under = offset > headerBottom - 4
                if under != docked { withAnimation(.smooth(duration: 0.25)) { docked = under } }
            }
        )
        .onGeometryChange(for: Bool.self) { WideGalleryPage.fits($0.size) } action: { wide = $0 }
        // The system bar (with its soft edge over the content): the site's
        // name in the middle (a menu that switches the site). The feed's
        // header (sort, language, search) sits above the feed and moves into
        // the bar once it has scrolled under it.
        .navigationTitle(settings.currentSite.displayName)
        .navigationBarTitleDisplayMode(.inline)
        // The title's menu switches the site: with one site on, there is no menu (and no arrow).
        .modifier(SiteTitleMenu(sites: settings.enabledSites, current: settings.currentSite) { settings.homeSite = $0 })
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
            } else if settings.currentSite == .nhentai {
                // nhentai's random gallery; the feed's language and search buttons
                // take its place once they have scrolled up into the bar.
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task {
                            if let id = try? await NHClient.shared.randomID() { randomRoute = GalleryRoute(id: id, preview: nil) }
                        }
                    } label: {
                        Label("Random Gallery", systemImage: "dice")
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

    /// The pinned rows, then the feed's header (sort, language, search).
    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            let pinned = pinnedRows
            if wide, pinned.count == 2 {
                // Wide (a tablet held sideways): the two rows side by side.
                HStack(alignment: .top, spacing: 12) {
                    ForEach(Array(pinned.enumerated()), id: \.element.id) { index, entry in
                        // Each row scrolls inside its own half, cut off where the halves meet.
                        VStack(alignment: .leading, spacing: 0) {
                            SectionTitle(title: LocalizedStringKey(entry.row.titleKey))
                            PopularRow(items: entry.items, cutEdges: index == 0 ? .trailing : .leading)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else {
                ForEach(pinned) { entry in
                    SectionTitle(title: LocalizedStringKey(entry.row.titleKey))
                    PopularRow(items: entry.items)
                }
            }
            FeedHeader { startSearch() }
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
            // All rows show together, not one after the other.
            var loaded: [HomeRow: [GalleryItem]] = [:]
            for await (row, items) in group {
                if let items { loaded[row] = items }
            }
            withAnimation(.smooth(duration: 0.25)) { rows.merge(loaded) { _, new in new } }
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
/// A section title with a link on the right ("See All"), the same on
/// every page. Tapping the title itself runs [onTitleTap] when given.
struct SectionHeader<Trailing: View>: View {
    let title: LocalizedStringKey
    var onTitleTap: (() -> Void)? = nil
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            SectionTitle(title: title)
                .fixedSize()
                .contentShape(Rectangle())
                .onTapGesture { onTitleTap?() }
            Spacer(minLength: 8)
            trailing()
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .padding(.trailing)
        }
    }
}

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
    /// Where the row is cut off (next to the other half of a wide page), not at the screen's edge.
    var cutEdges: Edge.Set = []

    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let width: CGFloat = sizeClass == .regular ? 170 : 132
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items) { item in
                    GalleryCard(item: item, style: .grid)
                        .frame(width: width)
                }
            }
            .scrollTargetLayout()
            .padding(.horizontal)
        }
        .scrollIndicators(.hidden)
        .scrollTargetBehavior(.viewAligned)
        // The covers are 3:4.
        .cutCards(cutEdges, imageHeight: width * 4 / 3)
    }
}

/// The site switch: the site's name in the middle of the bar, a menu of the
/// sites, with room around it to touch (the system's title menu is just the
/// text, and a touch a little above it scrolls the list to the top). Only
/// when there is a site to switch to; otherwise just the title.
private struct SiteTitleMenu: ViewModifier {
    let sites: [SiteKind]
    let current: SiteKind
    let select: (SiteKind) -> Void

    func body(content: Content) -> some View {
        if sites.count > 1 {
            if #available(iOS 26.0, *) {
                content.toolbar {
                    ToolbarItem(placement: .principal) { menu }
                        .sharedBackgroundVisibility(.hidden)
                }
            } else {
                content.toolbar {
                    ToolbarItem(placement: .principal) { menu }
                }
            }
        } else {
            content
        }
    }

    private var menu: some View {
        Menu {
            Picker("Site", selection: Binding(get: { current }, set: select)) {
                ForEach(sites) { Text($0.displayName).tag($0) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(current.displayName)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 28)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(Text(current.displayName))
    }
}
