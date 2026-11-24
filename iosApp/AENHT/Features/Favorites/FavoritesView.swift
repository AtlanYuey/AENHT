import Shared
import SwiftUI

/// Favorites of one site (live from the site, E-Hentai by folder), or of
/// both ("All", from the local copy, newest first, marked NH / EH). The
/// title's menu switches between them.
struct FavoritesView: View {
    @Environment(UserSession.self) private var session
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @State private var site: SiteKind? = AppSettings.shared.site(AppSettings.shared.favoritesSite)
    @State private var ehFolder: Int?
    @State private var nhFeed: GalleryFeed
    /// Searching inside the favorites (each site searches its own; All filters the copy).
    @State private var query = ""
    @State private var askQuery = false
    @State private var draftQuery = ""
    @State private var ehFeed: GalleryFeed
    @State private var folderBox = FolderBox()
    @State private var showLogin = false
    @State private var showEhLogin = false
    @State private var pickFolders = false
    /// When the list last reloaded because the tab was chosen.
    @State private var openedRefresh = Date.distantPast
    /// Choosing favorites to remove (Select, or Select in a gallery's context menu).
    @State private var selection: GallerySelection?
    @State private var confirmRemove = false
    /// Removing the chosen ones from the sites (one request each).
    @State private var removing = false
    @State private var removeError: String?
    private let store = FavoritesStore.shared

    /// Holds the folder for the E-Hentai feed's closure.
    @MainActor
    final class FolderBox {
        var folder: Int?
        var query = ""
    }

    init() {
        let box = FolderBox()
        _folderBox = State(initialValue: box)
        let nh = GalleryFeed.nh { page in try await NHClient.shared.favorites(page: page, query: box.query) }
        nh.filtered = false
        _nhFeed = State(initialValue: nh)
        let eh = GalleryFeed.eh { Bridge.shared.favorites(folder: Int32(box.folder ?? -1), query: box.query) }
        eh.filtered = false
        _ehFeed = State(initialValue: eh)
    }

    private func applyQuery(_ text: String) {
        query = text.trimmingCharacters(in: .whitespaces)
        folderBox.query = query
        Task {
            if site == .nhentai { await nhFeed.reload() } else if site == .ehentai { await ehFeed.reload() }
        }
    }

    /// Refresh Favorites When Opened is on, and the last reload on opening
    /// was a few seconds ago at least (tabs switched quickly).
    private func openedRefreshDue() -> Bool {
        guard settings.favoritesRefreshOnOpen, Date().timeIntervalSince(openedRefresh) > 5 else { return false }
        openedRefresh = Date()
        return true
    }

    /// What shows now (Select All chooses these).
    private var shownItems: [GalleryItem] {
        if let site {
            guard loggedIn(site) else { return [] }
            return (site == .ehentai ? ehFeed : nhFeed).items
        }
        return store.cards(folders: Set(settings.favoritesAllFolders))
            .filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }
    }

    private var chosenItems: [GalleryItem] {
        guard let selection else { return [] }
        let keys = Set(selection.keys)
        return shownItems.filter { keys.contains($0.uid) }
    }

    /// Removes [items] from the favorites on their sites, one at a time (the
    /// sites limit how fast); stops at the first error, which is shown. Those
    /// removed leave the lists and the saved copy.
    private func removeChosen(_ items: [GalleryItem]) async {
        guard !removing, !items.isEmpty else { return }
        removing = true
        var done = Set<String>()
        var failure: String?
        for item in items {
            do {
                if item.site == .ehentai {
                    try await EhService.shared.setFavorite(gid: item.id, token: item.token ?? "", folder: nil)
                } else {
                    _ = try await NHClient.shared.setFavorite(item.id, false)
                }
                done.insert(item.uid)
                await store.removed(item)
            } catch {
                failure = ErrorText.of(error)
                break
            }
        }
        nhFeed.remove(uids: done)
        ehFeed.remove(uids: done)
        removing = false
        if failure == nil { selection = nil } else { selection?.remove(done) }
        removeError = failure
    }

    private func loggedIn(_ site: SiteKind) -> Bool {
        site == .nhentai ? session.isLoggedIn : ehSession.isLoggedIn
    }

    var body: some View {
        Group {
            if let site, !loggedIn(site) {
                ContentUnavailableView {
                    Label("Favorites", systemImage: "heart")
                } description: {
                    Text(site == .ehentai ? "Log in to your E-Hentai account to see your favorites."
                                          : "Log in to your nhentai account to see your favorites.")
                } actions: {
                    Button("Log In") { if site == .ehentai { showEhLogin = true } else { showLogin = true } }
                        .modifier(ProminentButtonStyle())
                }
            } else if site == nil {
                let cards = store.cards(folders: Set(settings.favoritesAllFolders))
                GalleryList(
                    blocks: [
                        .view("top") { Color.clear.frame(height: 8) },
                        .cards("all", cards.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }),
                        .view("empty") {
                            if cards.isEmpty {
                                if store.syncing != nil {
                                    ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(.top, 80)
                                } else {
                                    ContentUnavailableView("No Favorites", systemImage: "heart",
                                                           description: Text(store.error ?? String(localized: "Log in to a site to see its favorites here.")))
                                }
                            }
                        },
                    ],
                    refresh: { await store.sync() },
                    tab: .favorites,
                    onTabOpened: { if openedRefreshDue() { await store.syncIfStale() } }
                )
                .environment(\.showsSiteBadge, true)
            } else {
                let feed = site == .ehentai ? ehFeed : nhFeed
                GalleryList(
                    blocks: [
                        .view("top") { Color.clear.frame(height: 8) },
                        .cards(site == .ehentai ? "eh" : "nh", feed.items),
                        .view("footer") {
                            VStack(spacing: 0) {
                                FeedPlaceholder(feed: feed, emptyTitle: "No Favorites", emptyImage: "heart")
                                FeedFooter(feed: feed)
                            }
                        },
                    ],
                    onItemAppear: { item in Task { await feed.loadMoreIfNeeded(after: item) } },
                    refresh: { await feed.reload() },
                    tab: .favorites,
                    onTabOpened: {
                        // Me › Sites › Refresh Favorites When Opened: a gallery just favorited shows.
                        guard openedRefreshDue(), let site, loggedIn(site), feed.hasLoaded, !feed.isLoading else { return }
                        await feed.reload()
                    }
                )
            }
        }
        // The system title menu, like Home's (not a tinted button).
        .navigationTitle("Favorites")
        .modifier(NavigationSubtitle(text: subtitle))
        .navigationBarTitleDisplayMode(.inline)
        .toolbarTitleMenu { titleMenuItems }
        .alert("Search Favorites", isPresented: $askQuery) {
            TextField("Search", text: $draftQuery)
            Button("Search") { applyQuery(draftQuery) }
            if !query.isEmpty { Button("Clear Search", role: .destructive) { applyQuery("") } }
            Button("Cancel", role: .cancel) {}
        }
        .barToolbar {
            if site.map(loggedIn) ?? true {
                ToolbarItem(placement: .topBarLeading) {
                    Button(selection == nil ? "Select" : "Done") {
                        selection = selection == nil ? GallerySelection() : nil
                    }
                    .fontWeight(selection == nil ? .regular : .semibold)
                    .disabled(removing || (selection == nil && shownItems.isEmpty))
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    draftQuery = query
                    askQuery = true
                } label: {
                    Label("Search Favorites", systemImage: query.isEmpty ? "magnifyingglass" : "magnifyingglass.circle.fill")
                }
            }
            if site == nil, let syncing = store.syncing {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 6) {
                        ProgressView()
                        Text("\(syncing.badge) \(store.syncedCount)").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .environment(\.gallerySelection, selection)
        .environment(\.gallerySelect) { item in if !removing { selection = GallerySelection(item) } }
        // While choosing: the selection bar takes the tab bar's place.
        .toolbar(selection == nil ? .automatic : .hidden, for: .tabBar)
        .safeAreaInset(edge: .bottom) {
            if let selection {
                GallerySelectionBar(selection: selection, items: shownItems, action: "Remove", busy: removing) {
                    confirmRemove = true
                }
            }
        }
        .confirmationDialog("Remove \(chosenItems.count) from favorites?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                let items = chosenItems
                Task { await removeChosen(items) }
            }
        } message: {
            Text("They are removed from your favorites on the site too.")
        }
        .alert("Couldn't Remove", isPresented: Binding(get: { removeError != nil }, set: { if !$0 { removeError = nil } })) {
            Button("OK") { removeError = nil }
        } message: {
            Text(removeError ?? "")
        }
        .sheet(isPresented: $showLogin) { LoginView() }
        .sheet(isPresented: $showEhLogin) { EhLoginView() }
        .sheet(isPresented: $pickFolders) { FolderPicker() }
        .task(id: "\(site?.rawValue ?? "all")-\(session.isLoggedIn)-\(ehSession.isLoggedIn)-\(ehFolder ?? -1)") {
            folderBox.folder = ehFolder
            switch site {
            case nil: await store.syncIfStale()
            case .nhentai?: if session.isLoggedIn, !nhFeed.hasLoaded { await nhFeed.reload() }
            case .ehentai?:
                if ehSession.isLoggedIn {
                    await ehSession.loadFolders()
                    await ehFeed.reload()
                }
            }
        }
        .onAppear {
            // "Same as Home" follows the site the home page is on now.
            if settings.favoritesSite == .follow { site = settings.site(.follow) }
        }
    }

    /// The title menu: the site, and E-Hentai's folders.
    @ViewBuilder private var titleMenuItems: some View {
            if settings.enabledSites.count > 1 {
                Button { site = nil } label: { checked("All", site == nil) }
            }
            ForEach(settings.enabledSites) { s in
                Button {
                    site = s
                    ehFolder = nil
                } label: { checked(LocalizedStringKey(s.displayName), site == s && (s != .ehentai || ehFolder == nil)) }
            }
            if site == .ehentai, ehSession.isLoggedIn {
                Section("Folders") {
                    ForEach(Array(ehSession.folderNames.enumerated()), id: \.offset) { index, name in
                        Button { ehFolder = index } label: { checked(LocalizedStringKey(name), ehFolder == index) }
                    }
                }
            }
            if site == nil, settings.enabledSites.contains(.ehentai) {
                Divider()
                Button("E-Hentai Folders Shown in All", systemImage: "folder") { pickFolders = true }
            }
    }

    private var subtitle: String {
        switch site {
        case nil: String(localized: "All")
        case .nhentai?: "nhentai"
        case .ehentai?: "E-Hentai · " + (ehFolder.flatMap { ehSession.folderNames.indices.contains($0) ? ehSession.folderNames[$0] : nil }
            ?? String(localized: "All Folders"))
        }
    }

    @ViewBuilder
    private func checked(_ title: LocalizedStringKey, _ on: Bool) -> some View {
        if on { Label(title, systemImage: "checkmark") } else { Text(title) }
    }
}

/// Which E-Hentai folders Favorites › All shows.
private struct FolderPicker: View {
    @Environment(AppSettings.self) private var settings
    @Environment(EhSession.self) private var ehSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(ehSession.folderNames.enumerated()), id: \.offset) { index, name in
                    Toggle(name, isOn: Binding(
                        get: { settings.favoritesAllFolders.contains(index) },
                        set: { on in
                            var folders = Set(settings.favoritesAllFolders)
                            if on { folders.insert(index) } else { folders.remove(index) }
                            settings.favoritesAllFolders = folders.sorted()
                        }
                    ))
                }
            }
            .navigationTitle("E-Hentai Folders Shown in All")
            .navigationBarTitleDisplayMode(.inline)
            .barToolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
