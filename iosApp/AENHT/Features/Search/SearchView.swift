import Shared
import SwiftUI

/// In-place search on the Home page: a search field at the top with tag
/// suggestions, recent searches and results, shown over the feed (which
/// keeps its scroll position). Cancel goes back to the feed.
struct InlineSearch: View {
    @Binding var isActive: Bool

    @Environment(AppSettings.self) private var settings
    @Environment(TagStore.self) private var tagStore
    @State private var text = ""
    @State private var query = ""
    @State private var suggestions: [TagSuggestion] = []
    /// The site searched; switched under the field with the results.
    @State private var site: SiteKind = AppSettings.shared.searchStartSite
    @FocusState private var focused: Bool
    /// The search follows a swipe from the left edge, which closes it (like going back).
    @State private var dragX: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            field
            content
        }
        .background(.background)
        .offset(x: dragX)
        .simultaneousGesture(
            DragGesture(minimumDistance: 12, coordinateSpace: .global)
                .onChanged { value in
                    guard value.startLocation.x < 32, value.translation.width > 0 else { return }
                    dragX = value.translation.width
                }
                .onEnded { value in
                    guard value.startLocation.x < 32 else { return }
                    if value.translation.width > 110 || value.predictedEndTranslation.width > 260 {
                        withAnimation(.smooth(duration: 0.22)) { dragX = UIScreen.main.bounds.width }
                        close()
                    } else {
                        withAnimation(.smooth(duration: 0.22)) { dragX = 0 }
                    }
                }
        )
        .onChange(of: text) { _, new in
            if new.isEmpty { query = "" }
        }
        .task(id: text) { await loadSuggestions() }
        .task {
            // The keyboard comes up as soon as the search opens.
            try? await Task.sleep(for: .milliseconds(80))
            focused = true
        }
    }

    private var field: some View {
        HStack(spacing: 10) {
            HStack(spacing: 6) {
                siteMenu
                TextField("Titles, tags, artists, IDs", text: $text)
                    .focused($focused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { submit(text) }
                if !text.isEmpty {
                    Button {
                        text = ""
                        focused = true
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.tertiary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Clear"))
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(.fill.tertiary, in: Capsule())
            Button("Cancel") { close() }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    /// The magnifier with the searched site on it; with both sites, a menu that switches it.
    @ViewBuilder private var siteMenu: some View {
        if settings.enabledSites.count > 1 {
            Menu {
                ForEach(settings.enabledSites) { s in
                    Button {
                        site = s
                    } label: {
                        if s == site { Label(s.displayName, systemImage: "checkmark") } else { Text(s.displayName) }
                    }
                }
            } label: {
                magnifier
            }
            .accessibilityLabel(Text("Site"))
        } else {
            magnifier
        }
    }

    private var magnifier: some View {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .overlay(alignment: .bottomTrailing) {                        // The site being searched, on the magnifier.
                    if settings.enabledSites.count > 1 {
                        Text(site.badge)
                            .font(.system(size: 7, weight: .heavy))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 2)
                            .background(site == .ehentai ? Color(red: 0.62, green: 0.15, blue: 0.13) : .accentColor,
                                        in: RoundedRectangle(cornerRadius: 3))
                            .offset(x: 8, y: 6)
                    }
                }
                .padding(.trailing, settings.enabledSites.count > 1 ? 6 : 0)
    }

    @ViewBuilder private var content: some View {
        if focused, !suggestions.isEmpty {
            suggestionList
        } else if query.isEmpty {
            recentList
        } else {
            SearchResults(query: query, site: $site)
                .id(query)
        }
    }

    private var suggestionList: some View {
        List(suggestions) { tag in
            Button {
                text = completion(for: tag)
                suggestions = []
            } label: {
                Label {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(tag.name)
                                .foregroundStyle(.primary)
                            if let translated = tagStore.translate(tag.name, type: tag.type) {
                                Text(translated).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text(tag.type.capitalized)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "tag")
                }
            }
        }
        .listStyle(.plain)
    }

    private var recentList: some View {
        List {
            if !settings.saveSearchHistory || settings.recentSearches.isEmpty {
                ContentUnavailableView(
                    site == .ehentai ? "Search E-Hentai" : "Search nhentai",
                    systemImage: "magnifyingglass",
                    description: Text(site == .ehentai ? "Search by title or use tags like artist:name$, or paste a gallery link."
                                                       : "Search by title or use tags like artist:name, or enter a gallery number.")
                )
                .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(settings.recentSearches, id: \.self) { item in
                        Button {
                            text = item
                            submit(item)
                        } label: {
                            Label(item, systemImage: "clock.arrow.circlepath")
                                .foregroundStyle(.primary)
                        }
                        .contextMenu {
                            Button("Delete", systemImage: "trash", role: .destructive) {
                                settings.removeRecentSearch(item)
                            }
                        }
                    }
                    .onDelete { settings.removeRecentSearches(at: $0) }
                } header: {
                    HStack {
                        Text("Recent Searches")
                        Spacer()
                        Button("Clear") { settings.clearRecentSearches() }
                            .font(.subheadline)
                    }
                }
            }
        }
        .scrollDismissesKeyboard(.immediately)
    }

    private func close() {
        focused = false
        withAnimation(.smooth(duration: 0.25)) { isActive = false }
    }

    /// Replaces the word being typed with the tag.
    private func completion(for tag: TagSuggestion) -> String {
        var parts = text.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        if !parts.isEmpty { parts.removeLast() }
        if site == .ehentai {
            let value = tag.name.contains(" ") ? "\"\(tag.name)$\"" : "\(tag.name)$"
            parts.append("\(tag.type):\(value)")
        } else {
            parts.append(tag.query)
        }
        return parts.joined(separator: " ") + " "
    }

    private func submit(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        settings.addRecentSearch(trimmed)
        suggestions = []
        focused = false
        query = trimmed
    }

    private func loadSuggestions() async {
        let word = text.split(separator: " ").last.map(String.init) ?? ""
        guard word.count >= 2, !word.contains(":"), !text.hasSuffix(" ") else {
            suggestions = []
            return
        }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        if site == .ehentai {
            suggestions = tagStore.suggestEh(word).map { TagSuggestion(type: $0.0, name: $0.1) }
        } else {
            suggestions = (try? await NHClient.shared.suggestTags(word)) ?? []
        }
    }
}

/// Search results for a query (also pushed from tags).
struct SearchResultsView: View {
    let route: SearchRoute
    @State private var site: SiteKind = .nhentai

    var body: some View {
        SearchResults(query: route.query, site: $site)
            .navigationTitle(route.title ?? route.query)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Results for a query on both sites: the site it was typed for first;
/// switching site under the field converts the query to the other site's
/// syntax. A gallery number (nhentai) or a gallery link shows that gallery
/// above the results.
struct SearchResults: View {
    let query: String
    @Binding var site: SiteKind
    /// Language and sort menus in the toolbar (the iPad Home page has its own).
    let showsMenus: Bool

    @Environment(AppSettings.self) private var settings
    @State private var nhFeed: GalleryFeed
    @State private var ehFeed: GalleryFeed
    @State private var match: GalleryItem?
    private let dropped: [SiteKind: [String]]
    private let link: String?

    init(query: String, site: Binding<SiteKind>, showsMenus: Bool = true) {
        self.query = query
        _site = site
        self.showsMenus = showsMenus
        let nhQuery: String
        let ehQuery: String
        var ehCategories: Int32 = 0
        if site.wrappedValue == .ehentai {
            let converted = Bridge.shared.ehToNh(query: query)
            nhQuery = converted.query
            ehQuery = query
            dropped = [.nhentai: converted.dropped]
        } else {
            let words = query.split(separator: " ").map { $0.split(separator: ":").last.map(String.init) ?? "" }
            var namespaces: [String: [String]] = [:]
            for word in words {
                let name = word.trimmingCharacters(in: CharacterSet(charactersIn: "\"$"))
                namespaces[name] = TagStore.shared.ehNamespaces(name)
            }
            let converted = Bridge.shared.nhToEh(query: query, namespaces: namespaces)
            nhQuery = query
            ehQuery = converted.query
            ehCategories = Bridge.shared.categoryBits(result: converted)
            dropped = [.ehentai: converted.dropped]
        }
        link = Bridge.shared.searchLink(text: query)
        _nhFeed = State(initialValue: GalleryFeed.nh { page in
            let settings = AppSettings.shared
            let full = [nhQuery, settings.searchLanguage.queryTerm].compactMap { $0 }.joined(separator: " ")
            return try await NHClient.shared.galleries(query: full, sort: settings.searchSort, page: page)
        })
        _ehFeed = State(initialValue: GalleryFeed.eh {
            let full = [ehQuery, AppSettings.shared.searchLanguage.queryTerm].compactMap { $0 }.joined(separator: " ")
            return Bridge.shared.search(query: full, categoriesShown: ehCategories)
        })
    }

    /// A pasted E-Hentai link is opened, not searched for.
    private var searches: Bool { link == nil || link!.hasPrefix("nh:") }
    private var feed: GalleryFeed { site == .ehentai ? ehFeed : nhFeed }

    var body: some View {
        @Bindable var settings = settings
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let match {
                    SectionTitle(title: match.site == .ehentai ? "E-Hentai Gallery" : "Gallery #\(match.id)")
                    GalleryRow(item: match)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
                if searches, settings.enabledSites.count > 1 {
                    Picker("Site", selection: $site) {
                        ForEach(settings.enabledSites) { Text($0.displayName).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                }
                if searches {
                    if let terms = dropped[site], !terms.isEmpty {
                        Text("Not available on \(site.displayName): \(terms.joined(separator: " "))")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .padding(.horizontal)
                            .padding(.top, 4)
                    }
                    if let total = feed.total, feed.hasLoaded {
                        Text("\(total) results")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                    }
                    GalleryCollection(items: feed.items) { item in
                        Task { await feed.loadMoreIfNeeded(after: item) }
                    }
                    if match == nil {
                        FeedPlaceholder(feed: feed, emptyTitle: "No Results", emptyImage: "magnifyingglass")
                    }
                    FeedFooter(feed: feed)
                }
            }
        }
        .refreshable { await feed.reload() }
        .hidesBarsOnScroll()
        .task(id: site) {
            guard searches else { return }
            if !feed.hasLoaded { await feed.reload() }
            // Both sites at once when the setting says so (twice the requests).
            if settings.searchBothSites, settings.enabledSites.count > 1 {
                let other = site == .ehentai ? nhFeed : ehFeed
                if !other.hasLoaded { await other.reload() }
            }
        }
        .task { await loadMatch() }
        .toolbar {
            if showsMenus {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Picker("Language", selection: $settings.searchLanguage) {
                            ForEach(LanguageFilter.allCases) { Text($0.title).tag($0) }
                        }
                    } label: {
                        Label("Language", systemImage: "globe")
                    }
                    Menu {
                        Picker("Sort", selection: $settings.searchSort) {
                            ForEach(SearchSort.allCases) { Text($0.title).tag($0) }
                        }
                    } label: {
                        Label("Sort", systemImage: "arrow.up.arrow.down")
                    }
                }
            }
        }
        .onChange(of: settings.searchLanguage) { Task { await feed.reload() } }
        .onChange(of: settings.searchSort) { Task { await feed.reload() } }
    }

    /// The gallery a number or a link points to.
    private func loadMatch() async {
        guard match == nil, let link else { return }
        let parts = link.split(separator: ":").map(String.init)
        switch parts.first {
        case "nh":
            if let id = Int(parts[1]) { match = try? await NHClient.shared.gallery(id).asItem }
        case "eh":
            if parts.count == 3, let gid = Int(parts[1]) { match = try? await EhService.shared.info(gid: gid, token: parts[2]) }
        case "ehpage":
            if parts.count == 4, let gid = Int(parts[1]), let page = Int(parts[3]),
               let token = try? await EhService.shared.token(gid: gid, pageToken: parts[2], page: page) {
                match = try? await EhService.shared.info(gid: gid, token: token)
            }
        default: break
        }
    }
}
