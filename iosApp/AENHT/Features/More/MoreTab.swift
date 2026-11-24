import SwiftData
import SwiftUI
import UIKit

/// The Me tab. On a wide screen (an iPad held sideways) it is two columns:
/// the Me page on the left, and on the right the page of the entry chosen
/// there, with everything opened from it (the pages two levels down and
/// further stay on the right). Otherwise the Me page, whose entries push
/// their pages.
struct MoreTab: View {
    static let columnWidth: CGFloat = 400

    @State private var size: CGSize = .zero
    @State private var page: MorePage? = .overview
    @Environment(AppSettings.self) private var settings

    /// An iPad held sideways: Tablet Layout is offered.
    private var tabletLandscape: Bool {
        UIDevice.current.userInterfaceIdiom == .pad && size.width > size.height
    }

    private var wide: Bool { settings.tabletMe && WideGalleryPage.fits(size) }

    var body: some View {
        Group {
            if wide {
                NavigationSplitView(columnVisibility: .constant(.all)) {
                    MoreView(selection: $page, showsTabletLayout: tabletLandscape)
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
                    MoreView(showsTabletLayout: tabletLandscape).galleryDestinations()
                }
            }
        }
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
        // Turned upright on the Tablet Layout page: back to the overview (its entry is gone).
        .onChange(of: tabletLandscape) { _, shown in
            if !shown, page == .tabletLayout { page = .overview }
        }
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
        SettingsForm {
            // The logged-in accounts side by side (nhentai first), each opening Accounts.
            Section("Accounts") {
                entry(.accounts) {
                    HStack(spacing: 12) {
                        let nh = settings.nhentaiEnabled && session.isLoggedIn
                        let eh = settings.ehentaiEnabled && ehSession.isLoggedIn
                        if nh {
                            account(name: session.user?.username ?? "nhentai", site: "nhentai",
                                    avatar: AvatarView(url: session.user?.avatar, size: 40))
                        }
                        if eh {
                            account(name: ehSession.hasExHentai ? "E-Hentai · ExHentai" : "E-Hentai",
                                    site: String(localized: "Logged In"),
                                    avatar: AvatarView(url: nil, size: 40, monogram: "EH"))
                        }
                        if !nh, !eh {
                            account(name: String(localized: "Not logged in"), site: String(localized: "Accounts"),
                                    avatar: AvatarView(url: nil, size: 40))
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

    /// One account: its picture, name and site, taking an equal share of the row.
    private func account(name: String, site: String, avatar: AvatarView) -> some View {
        HStack(spacing: 10) {
            avatar
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline).lineLimit(1)
                Text(site).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
        AppVersion.display
    }
}
