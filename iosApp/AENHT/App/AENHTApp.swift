import Shared
import SwiftData
import SwiftUI
import UIKit

@main
struct AENHTApp: App {
    @State private var settings = AppSettings.shared
    @State private var session = UserSession.shared
    @State private var downloads = DownloadManager.shared
    @State private var tagStore = TagStore.shared
    @State private var ehSession = EhSession.shared

    init() {
        ImageSetup.configure()
        SettingsSync.shared.start(isPad: UIDevice.current.userInterfaceIdiom == .pad)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(settings)
                .environment(session)
                .environment(downloads)
                .environment(tagStore)
                .environment(ehSession)
                .tint(settings.accent.color)
                .preferredColorScheme(settings.theme.colorScheme)
                .modifier(SoftScrollEdges())
                .task { await session.reload() }
                .task { await tagStore.prepare() }
        }
        .modelContainer(AppModel.container)
    }
}

enum AppTab: Hashable {
    case home, favorites, history, more, search
}

/// The tabs with a floating glass tab bar at the bottom (PadRootView).
struct RootView: View {
    @State private var selection: AppTab = .home
    @Environment(AppSettings.self) private var settings
    @Environment(\.scenePhase) private var scenePhase
    @State private var clipboardLink: ClipboardLink?
    @State private var openedFromClipboard: ClipboardLink?

    var body: some View {
        // Search is on the Home page, so neither has a search tab.
        Group {
            if UIDevice.current.userInterfaceIdiom == .pad {
                // iPad: the floating glass tab bar at the bottom (PadRootView).
                PadRootView(selection: $selection)
            } else {
                phoneTabs
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { checkClipboard() }
            if phase == .active { WebDavService.shared.syncIfDue() }
            if phase == .background { WebDavService.shared.syncIfDue(minutes: 1) }
        }
        .alert("Open Gallery?", isPresented: Binding(
            get: { clipboardLink != nil },
            set: { if !$0 { clipboardLink = nil } }
        )) {
            Button("Open") {
                openedFromClipboard = clipboardLink
                clipboardLink = nil
            }
            Button("Cancel", role: .cancel) { clipboardLink = nil }
        } message: {
            switch clipboardLink {
            case let .nh(id)?: Text("The clipboard contains a link to gallery #\(id).")
            case let .eh(gid, _)?: Text("The clipboard contains a link to an E-Hentai gallery (\(gid)).")
            case nil: EmptyView()
            }
        }
        .sheet(item: $openedFromClipboard) { link in
            NavigationStack {
                Group {
                    switch link {
                    case let .nh(id): GalleryDetailView(route: GalleryRoute(id: id))
                    case let .eh(gid, token): EhGalleryView(route: EhGalleryRoute(gid: gid, token: token))
                    }
                }
                    .galleryDestinations()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close", systemImage: "xmark") { openedFromClipboard = nil }
                        }
                    }
            }
        }
    }

    /// iPhone: the system tab bar. With Hide Tab Bar When Scrolling on,
    /// iOS 26 shrinks it to a small button while a list scrolls down.
    private var phoneTabs: some View {
        TabView(selection: $selection) {
            Tab("Home", systemImage: "house", value: AppTab.home) {
                NavigationStack {
                    HomeView().galleryDestinations()
                }
            }
            Tab("Favorites", systemImage: "heart", value: AppTab.favorites) {
                NavigationStack {
                    FavoritesView().galleryDestinations()
                }
            }
            Tab("History", systemImage: "clock", value: AppTab.history) {
                NavigationStack {
                    HistoryView().galleryDestinations()
                }
            }
            Tab("Me", systemImage: "person.crop.circle", value: AppTab.more) {
                NavigationStack {
                    MoreView().galleryDestinations()
                }
            }
        }
        .modifier(TabBarMinimize(enabled: settings.hideBarsOnScroll))
    }

    /// Offers to open a gallery link (nhentai, E-Hentai, ExHentai) found on the clipboard, once per link.
    private func checkClipboard() {
        guard settings.detectClipboard, UIPasteboard.general.hasURLs || UIPasteboard.general.hasStrings else {
            return
        }
        let text = UIPasteboard.general.url?.absoluteString ?? UIPasteboard.general.string ?? ""
        guard let link = Bridge.shared.findLink(text: text) else { return }
        let key = "lastClipboardLink"
        guard UserDefaults.standard.string(forKey: key) != link else { return }
        UserDefaults.standard.set(link, forKey: key)
        let parts = link.split(separator: ":").map(String.init)
        switch parts.first {
        case "nh" where settings.enabledSites.contains(.nhentai):
            if let id = Int(parts[1]) { clipboardLink = .nh(id) }
        case "eh" where settings.enabledSites.contains(.ehentai):
            if parts.count == 3, let gid = Int(parts[1]) { clipboardLink = .eh(gid, parts[2]) }
        case "ehpage" where settings.enabledSites.contains(.ehentai):
            // An image page link: its gallery's token is looked up first.
            if parts.count == 4, let gid = Int(parts[1]), let page = Int(parts[3]) {
                Task {
                    if let token = try? await EhService.shared.token(gid: gid, pageToken: parts[2], page: page) {
                        clipboardLink = .eh(gid, token)
                    }
                }
            }
        default: break
        }
    }
}

/// A gallery link found on the clipboard.
enum ClipboardLink: Identifiable, Hashable {
    case nh(Int)
    case eh(Int, String)

    var id: String {
        switch self {
        case let .nh(id): "nh:\(id)"
        case let .eh(gid, token): "eh:\(gid):\(token)"
        }
    }
}

extension GalleryRoute: Identifiable {}

/// iOS 26: the system tab bar shrinks to a small button while a list
/// scrolls down (the system handles it, so lists don't move).
private struct TabBarMinimize: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(enabled ? .onScrollDown : .never)
        } else {
            content
        }
    }
}

/// iOS 26's soft, progressive scroll edge effect under bars, for every
/// scroll view in the app (instead of whatever the system default is).
struct SoftScrollEdges: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.scrollEdgeEffectStyle(.soft, for: .all)
        } else {
            content
        }
    }
}

