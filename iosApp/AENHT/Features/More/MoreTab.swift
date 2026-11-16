import SwiftData
import SwiftUI

/// The Me tab. On a wide screen (an iPad held sideways) it is two columns:
/// the Me page on the left, and on the right the page of the entry chosen
/// there, with everything opened from it (the pages two levels down and
/// further stay on the right). Otherwise the Me page, whose entries push
/// their pages.
struct MoreTab: View {
    static let columnWidth: CGFloat = 400

    @State private var wide = false
    @State private var page: MorePage? = .overview

    var body: some View {
        Group {
            if wide {
                NavigationSplitView(columnVisibility: .constant(.all)) {
                    MoreView(selection: $page)
                        .navigationSplitViewColumnWidth(Self.columnWidth)
                        .toolbar(removing: .sidebarToggle)
                        // The tab bar is at the bottom of this column.
                        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: TabNavigators.shared.bottomInset) }
                } detail: {
                    RoutedNavigationStack {
                        MorePage.view(for: page ?? .overview, selection: $page)
                            .galleryDestinations()
                            // The Me tab button goes back one page at a time, on this side.
                            .background(TabNavigationFinder(tab: .more).frame(width: 0, height: 0))
                    }
                    // Another entry starts its own pages.
                    .id(page ?? .overview)
                }
                .navigationSplitViewStyle(.balanced)
            } else {
                RoutedNavigationStack {
                    MoreView().galleryDestinations()
                }
            }
        }
        .onGeometryChange(for: Bool.self) { WideGalleryPage.fits($0.size) } action: { wide = $0 }
    }
}

/// The right column before an entry is chosen: how things stand, each part
/// opening its page.
struct MoreOverview: View {
    @Binding var selection: MorePage?

    @Environment(UserSession.self) private var session
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @Environment(DownloadManager.self) private var downloads
    @State private var favorites = FavoritesStore.shared
    @State private var webDav = WebDavService.shared
    @State private var iCloud = SettingsSync.shared
    @State private var usage: Int64?
    @Query private var history: [HistoryEntry]

    var body: some View {
        Form {
            Section("Accounts") {
                if settings.nhentaiEnabled {
                    entry(.accounts) {
                        HStack(spacing: 12) {
                            AvatarView(url: session.isLoggedIn ? session.user?.avatar : nil, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("nhentai").font(.headline)
                                Text(session.isLoggedIn ? (session.user?.username ?? String(localized: "Logged In")) : String(localized: "Not logged in"))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if settings.ehentaiEnabled {
                    entry(.accounts) {
                        HStack(spacing: 12) {
                            AvatarView(url: nil, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("E-Hentai").font(.headline)
                                Text(ehSession.isLoggedIn ? (ehSession.hasExHentai ? "E-Hentai · ExHentai" : String(localized: "Logged In"))
                                                          : String(localized: "Not logged in"))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            Section("Downloads") {
                entry(.downloads) {
                    LabeledContent("Downloading", value: "\(downloads.records.filter { $0.state != .finished }.count)")
                }
                entry(.downloads) {
                    LabeledContent("Downloaded", value: "\(downloads.records.filter { $0.state == .finished }.count)")
                }
                entry(.downloads) {
                    LabeledContent("Storage") {
                        if let usage { Text(usage, format: .byteCount(style: .file)) } else { ProgressView() }
                    }
                }
            }

            Section {
                LabeledContent("History", value: "\(history.count)")
                LabeledContent("Favorites", value: "\(favorites.items.count)")
            }

            Section {
                entry(.iCloud) {
                    LabeledContent("iCloud Sync", value: iCloudStatus)
                }
                entry(.webDav) {
                    LabeledContent("WebDAV Sync", value: webDavStatus)
                }
            }

            Section {
                entry(.about) {
                    LabeledContent("Version", value: version)
                }
            }
        }
        .navigationTitle("All")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: downloads.records.count) {
            usage = await Task.detached { DownloadManager.diskUsage() }.value
        }
    }

    /// A part that opens [page] in this column.
    private func entry<Label: View>(_ page: MorePage, @ViewBuilder label: () -> Label) -> some View {
        Button { selection = page } label: {
            HStack {
                label()
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func synced(_ date: Date?) -> String {
        guard let date else { return String(localized: "Not synced yet") }
        return String(localized: "Last synced: \(date.formatted(date: .abbreviated, time: .shortened))")
    }

    private var iCloudStatus: String {
        iCloud.isEnabled ? synced(iCloud.lastSync) : String(localized: "Off")
    }

    private var webDavStatus: String {
        webDav.isConfigured ? (webDav.error ?? synced(webDav.lastSync)) : String(localized: "Not set up")
    }

    private var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "-") (\(info?["CFBundleVersion"] as? String ?? "-"))"
    }
}
