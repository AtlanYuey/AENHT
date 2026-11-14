import SwiftUI

struct HomeView: View {
    @Environment(AppSettings.self) private var settings
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

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                SiteTitle()
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
        .onScrollGeometryChange(for: Bool.self) { geometry in
            // The bar's bottom edge, in the feed's coordinates, is past the header.
            geometry.contentOffset.y + geometry.contentInsets.top > headerBottom - 4
        } action: { _, under in
            withAnimation(.smooth(duration: 0.25)) { docked = under }
        }
        .hidesBarsOnScroll()
        // No navigation bar: the page starts with the site's name (SiteTitle)
        // right under the status bar; a small bar comes in once the feed's
        // header has scrolled away.
        .navigationTitle(settings.currentSite.displayName)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .top) {
            if docked, !searching {
                DockedBar(onSearch: startSearch)
                    .transition(.move(edge: .top).combined(with: .opacity))
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

/// The bar over the feed once its header has scrolled away: the site, the
/// sort, the language and search.
private struct DockedBar: View {
    let onSearch: () -> Void

    @Environment(AppSettings.self) private var settings

    var body: some View {
        HStack(spacing: 14) {
            SortMenu(compact: true)
            Spacer(minLength: 8)
            Text(settings.currentSite.displayName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 8)
            LanguageMenu(compact: true)
                .labelStyle(.iconOnly)
            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
            }
            .accessibilityLabel(Text("Search"))
        }
        .font(.body.weight(.semibold))
        .tint(.primary)
        .padding(.horizontal)
        .frame(height: 44)
        .frame(maxWidth: .infinity)
        .background(.bar, ignoresSafeAreaEdges: .top)
    }
}

/// The feed's header: the sort (a button that switches it), and on the
/// right the language and search buttons. Scrolled under the navigation
/// bar, the same buttons show in the bar.
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

/// The page's large title: the site Home shows ("nhentai", "E-Hentai",
/// "ExHentai"). With both sites on, it is the menu that switches them.
private struct SiteTitle: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        Group {
            if settings.enabledSites.count > 1 {
                Menu {
                    Picker("Site", selection: Binding(get: { settings.currentSite }, set: { settings.homeSite = $0 })) {
                        ForEach(settings.enabledSites) { Text($0.displayName).tag($0) }
                    }
                } label: {
                    label(chevron: true)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Site"))
            } else {
                label(chevron: false)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
        .padding(.top, 2)
    }

    private func label(chevron: Bool) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(settings.currentSite.displayName)
                .font(.largeTitle.bold())
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if chevron {
                Image(systemName: "chevron.down")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}

private struct CapsuleGlass: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            if #available(iOS 26.0, *) {
                content.glassEffect(.regular.interactive(), in: Capsule())
            } else {
                content.background(.regularMaterial, in: Capsule())
            }
        } else {
            content
        }
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
