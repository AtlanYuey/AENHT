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
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        GalleryCollection(items: store.cards(folders: Set(settings.favoritesAllFolders)).filter {
                            query.isEmpty || $0.title.localizedCaseInsensitiveContains(query)
                        })
                            .environment(\.showsSiteBadge, true)
                            .padding(.top, 8)
                        if store.items.isEmpty {
                            if store.syncing != nil {
                                ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(.top, 80)
                            } else {
                                ContentUnavailableView("No Favorites", systemImage: "heart",
                                                       description: Text(store.error ?? String(localized: "Log in to a site to see its favorites here.")))
                            }
                        }
                    }
                }
                .refreshable { await store.sync() }
                .hidesBarsOnScroll()
            } else {
                let feed = site == .ehentai ? ehFeed : nhFeed
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        GalleryCollection(items: feed.items) { item in
                            Task { await feed.loadMoreIfNeeded(after: item) }
                        }
                        .padding(.top, 8)
                        FeedPlaceholder(feed: feed, emptyTitle: "No Favorites", emptyImage: "heart")
                        FeedFooter(feed: feed)
                    }
                }
                .refreshable { await feed.reload() }
                .hidesBarsOnScroll()
            }
        }
        .navigationTitle("Favorites")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Search Favorites", isPresented: $askQuery) {
            TextField("Search", text: $draftQuery)
            Button("Search") { applyQuery(draftQuery) }
            if !query.isEmpty { Button("Clear Search", role: .destructive) { applyQuery("") } }
            Button("Cancel", role: .cancel) {}
        }
        .toolbar {
            ToolbarItem(placement: .principal) { titleMenu }
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

    /// "Favorites ▾" with the site / folder below it.
    private var titleMenu: some View {
        Menu {
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
        } label: {
            VStack(spacing: 0) {
                HStack(spacing: 4) {
                    Text("Favorites").font(.headline)
                    Image(systemName: "chevron.down").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                }
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            .foregroundStyle(.primary)
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
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
