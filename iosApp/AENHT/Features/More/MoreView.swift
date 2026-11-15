import Shared
import SwiftUI
import UIKit

struct MoreView: View {
    @Environment(UserSession.self) private var session
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @Environment(DownloadManager.self) private var downloads
    @State private var showLogin = false

    private var accountsSummary: String {
        var parts: [String] = []
        if settings.nhentaiEnabled, session.isLoggedIn { parts.append("nhentai") }
        if settings.ehentaiEnabled, ehSession.isLoggedIn { parts.append(ehSession.hasExHentai ? "E-Hentai · ExHentai" : "E-Hentai") }
        return parts.isEmpty ? String(localized: "Not logged in") : parts.joined(separator: " · ")
    }

    var body: some View {
        List {
            Section {
                // Both sites' accounts are one row; the next screen has a section per site.
                NavigationLink {
                    AccountsView()
                } label: {
                    HStack(spacing: 14) {
                        AvatarView(url: session.isLoggedIn ? session.user?.avatar : nil, size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Accounts")
                                .font(.title3.weight(.semibold))
                            Text(accountsSummary)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Section {
                NavigationLink(value: DownloadsRoute()) {
                    SettingsLabel("Downloads", systemImage: "arrow.down.circle.fill", color: .green)
                        .badge(downloads.records.filter { $0.state == .downloading }.count)
                }
            }

            Section {
                NavigationLink { SitesSettingsView() } label: {
                    SettingsLabel("Sites", systemImage: "square.stack.3d.up.fill", color: .pink)
                }
                NavigationLink { FiltersSettingsView() } label: {
                    SettingsLabel("Filters", systemImage: "eye.slash.fill", color: .gray)
                }
                NavigationLink { TranslationSettingsView() } label: {
                    SettingsLabel("Translation", systemImage: "character.bubble.fill", color: .green)
                }
                NavigationLink { AppearanceSettingsView() } label: {
                    SettingsLabel("Appearance", systemImage: "textformat.size", color: .blue)
                }
                NavigationLink { ReadingSettingsView() } label: {
                    SettingsLabel("Reading", systemImage: "book.fill", color: .orange)
                }
                NavigationLink { GeneralSettingsView() } label: {
                    SettingsLabel("General", systemImage: "gearshape.fill", color: .gray)
                }
                NavigationLink { SyncSettingsView() } label: {
                    SettingsLabel("iCloud Sync", systemImage: "icloud.fill", color: .cyan)
                }
                NavigationLink { WebDavSettingsView() } label: {
                    SettingsLabel("WebDAV Sync", systemImage: "externaldrive.fill.badge.icloud", color: .blue)
                }
            }

            Section {
                NavigationLink { LanguageSettingsView() } label: {
                    SettingsLabel("Language", systemImage: "globe", color: .teal)
                }
                NavigationLink { AboutView() } label: {
                    SettingsLabel("About", systemImage: "info", color: .indigo)
                }
            }
        }
        .navigationTitle("Me")
        // The Me tab button goes back one page at a time (AppChrome.tabReselected).
        .background(TabNavigationFinder(tab: .more).frame(width: 0, height: 0))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showLogin) { LoginView() }
    }
}

struct AvatarView: View {
    let url: URL?
    let size: CGFloat

    var body: some View {
        Group {
            if let url {
                RemoteImage(url: url, targetWidth: size * 2)
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .resizable()
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

/// The signed-in account: profile and log out.
struct AccountView: View {
    @Environment(UserSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var confirmLogout = false

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    AvatarView(url: session.user?.avatar, size: 96)
                    Text(session.user?.username ?? String(localized: "Logged In"))
                        .font(.title2.bold())
                    Text("nhentai account")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            if let user = session.user {
                Section {
                    LabeledContent("User ID", value: "\(user.id)")
                    Link(destination: NHHost.site.appendingPathComponent("users/\(user.id)/")) {
                        LabeledContent("Profile", value: "nhentai.net")
                    }
                }
            }
            Section {
                Button("Log Out", role: .destructive) { confirmLogout = true }
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Account")
        .navigationBarTitleDisplayMode(.inline)
        .task { await session.reload() }
        .confirmationDialog("Log out of nhentai?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Log Out", role: .destructive) {
                session.logout()
                dismiss()
            }
        }
    }
}

/// Galleries saved for offline reading: cards like Favorites, or rows with
/// their progress. A gallery opens on its page (offline too); Read there,
/// or in its menu, opens the pages.
struct DownloadsView: View {
    @Environment(DownloadManager.self) private var downloads
    @Environment(AppSettings.self) private var settings
    @State private var usage: Int64?
    @State private var site: SiteKind? = AppSettings.shared.site(AppSettings.shared.downloadsSite)
    @State private var selection = Set<String>()
    @State private var editMode: EditMode = .inactive

    private var records: [DownloadRecord] {
        guard let site else { return downloads.records }
        return downloads.records.filter { $0.siteKind == site }
    }

    var body: some View {
        Group {
            if settings.libraryStyle == .cards {
                cards
            } else {
                list
            }
        }
        .overlay {
            if records.isEmpty {
                ContentUnavailableView("No Downloads", systemImage: "arrow.down.circle",
                                       description: Text("Download a gallery from its page to read it offline."))
            }
        }
        .navigationTitle("Downloads")
        .navigationBarTitleDisplayMode(.inline)
        .environment(\.showsSiteBadge, site == nil && settings.enabledSites.count > 1)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { SiteFilterMenu(site: $site) }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink { DownloadSettingsView() } label: { Label("Download Settings", systemImage: "gearshape") }
            }
            if settings.libraryStyle != .cards, !records.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(editMode.isEditing ? "Done" : "Select") {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                            selection.removeAll()
                        }
                    }
                }
            }
            if let usage, usage > 0 {
                ToolbarItem(placement: .topBarTrailing) {
                    Text(usage, format: .byteCount(style: .file))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .task(id: downloads.records.count) {
            usage = await Task.detached { DownloadManager.diskUsage() }.value
        }
    }

    private var cards: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                GalleryCollection(items: records.map(\.item))
                    .padding(.top, 8)
            }
            .padding(.bottom, 16)
        }
        .keepsPlaceOnResize()
        .environment(\.galleryCoverBadge) { item in
            AnyView(DownloadBadge(uid: item.uid))
        }
        .environment(\.galleryMenuItems) { item in
            AnyView(DownloadMenuItems(uid: item.uid))
        }
    }

    /// In the list style: unfinished downloads first, then finished ones; Select for several at once.
    private var list: some View {
        let active = records.filter { $0.state != .finished }
        let finished = records.filter { $0.state == .finished }
        return List(selection: $selection) {
            if !active.isEmpty {
                Section("Downloading") { rows(active) }
            }
            if !finished.isEmpty {
                Section("Downloaded") { rows(finished) }
            }
        }
        .listStyle(.plain)
        .environment(\.editMode, $editMode)
        .safeAreaInset(edge: .bottom) {
            if editMode.isEditing { selectionBar }
        }
    }

    private func rows(_ records: [DownloadRecord]) -> some View {
        ForEach(records, id: \.uid) { record in
            DownloadRow(record: record)
                .tag(record.uid)
                .swipeActions(edge: .trailing) {
                    Button("Delete", systemImage: "trash", role: .destructive) {
                        downloads.delete(uid: record.uid)
                    }
                }
                .contextMenu {
                    DownloadMenuItems(uid: record.uid)
                }
        }
    }

    private var selectionBar: some View {
        let chosen = records.filter { selection.contains($0.uid) }
        return HStack {
            Button("Select All") { selection = Set(records.map(\.uid)) }
            Spacer()
            Button("Pause", systemImage: "pause") {
                chosen.filter { $0.state == .downloading || $0.state == .queued }.forEach { downloads.pause(uid: $0.uid) }
            }
            .disabled(!chosen.contains { $0.state == .downloading || $0.state == .queued })
            Button("Resume", systemImage: "arrow.clockwise") {
                chosen.filter { $0.state == .paused || $0.state == .failed }.forEach { downloads.resume(uid: $0.uid) }
            }
            .disabled(!chosen.contains { $0.state == .paused || $0.state == .failed })
            Button("Delete", systemImage: "trash", role: .destructive) {
                chosen.forEach { downloads.delete(uid: $0.uid) }
                selection.removeAll()
            }
            .disabled(chosen.isEmpty)
        }
        .labelStyle(.iconOnly)
        .font(.body.weight(.medium))
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .modifier(BarBackground())
    }
}

/// Read, pause / resume and delete for a download.
private struct DownloadMenuItems: View {
    let uid: String

    @Environment(DownloadManager.self) private var downloads

    var body: some View {
        if let record = downloads.record(uid: uid) {
            if record.completed > 0 {
                NavigationLink(value: ReaderRoute(source: ReaderSource(record: record), page: 0)) {
                    Label("Read", systemImage: "book")
                }
            }
            switch record.state {
            case .downloading, .queued:
                Button("Pause", systemImage: "pause") { downloads.pause(uid: uid) }
            case .paused:
                Button("Resume", systemImage: "arrow.clockwise") { downloads.resume(uid: uid) }
            case .failed:
                Button("Retry", systemImage: "arrow.clockwise") { downloads.resume(uid: uid) }
            case .finished:
                Button("Share as CBZ", systemImage: "doc.zipper") {
                    Task.detached {
                        if let url = try? DownloadManager.exportCBZ(record) {
                            await MainActor.run { ShareSheet.present([url]) }
                        }
                    }
                }
            }
            Button("Delete", systemImage: "trash", role: .destructive) { downloads.delete(uid: uid) }
        }
    }
}

/// A download's progress over its cover until it has finished.
private struct DownloadBadge: View {
    let uid: String

    @Environment(DownloadManager.self) private var downloads

    var body: some View {
        if let record = downloads.record(uid: uid), record.state != .finished {
            ZStack {
                Circle().fill(.black.opacity(0.55))
                Circle()
                    .stroke(.white.opacity(0.3), lineWidth: 3)
                    .padding(4)
                Circle()
                    .trim(from: 0, to: record.progress)
                    .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(4)
                Image(systemName: icon(record.state))
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white)
            }
            .frame(width: 34, height: 34)
            .padding(6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .animation(.smooth, value: record.progress)
            .accessibilityElement()
            .accessibilityLabel(Text("\(record.completed) / \(record.totalPages)"))
        }
    }

    private func icon(_ state: DownloadRecord.State) -> String {
        switch state {
        case .downloading: "arrow.down"
        case .queued: "clock"
        case .paused: "pause.fill"
        case .failed: "exclamationmark"
        case .finished: "checkmark"
        }
    }
}

private struct DownloadRow: View {
    let record: DownloadRecord

    @Environment(DownloadManager.self) private var downloads

    var body: some View {
        GalleryLinkButton(item: record.item) {
            HStack(spacing: 14) {
                CoverView(item: record.item, aspectRatio: 0.72, cornerRadius: 10, targetWidth: 200)
                    .frame(width: 72)
                VStack(alignment: .leading, spacing: 6) {
                    Text(record.item.prettyTitle)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    switch record.state {
                    case .finished:
                        Label("\(record.totalPages) pages", systemImage: "checkmark.circle.fill")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    default:
                        ProgressView(value: record.progress)
                        HStack {
                            Text("\(record.completed) / \(record.totalPages)")
                                .monospacedDigit()
                            Spacer()
                            stateButton
                        }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private var stateButton: some View {
        switch record.state {
        case .downloading, .queued:
            Button("Pause") { downloads.pause(uid: record.uid) }
                .buttonStyle(.borderless)
        case .paused:
            Button("Resume") { downloads.resume(uid: record.uid) }
                .buttonStyle(.borderless)
        case .failed:
            Button("Retry") { downloads.resume(uid: record.uid) }
                .buttonStyle(.borderless)
        case .finished:
            EmptyView()
        }
    }
}

/// A Settings-app style row label with a colored icon tile.
struct SettingsLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    let color: Color

    init(_ title: LocalizedStringKey, systemImage: String, color: Color) {
        self.title = title
        self.systemImage = systemImage
        self.color = color
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 30, height: 30)
                .background(color.gradient, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
    }
}

// MARK: - Appearance

struct AppearanceSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section("Theme") {
                Picker("Appearance", selection: $settings.theme) {
                    ForEach(ThemeMode.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowSeparator(.hidden)
                AccentPicker(selection: $settings.accent)
            }
            Section {
                Picker("List Style", selection: $settings.listStyle) {
                    ForEach(GalleryListStyle.allCases) { style in
                        Label(style.title, systemImage: style.systemImage).tag(style)
                    }
                }
                Picker("History and Downloads", selection: $settings.libraryStyle) {
                    ForEach(LibraryStyle.allCases) { Text($0.title).tag($0) }
                }
                NavigationLink {
                    CardSizeView()
                } label: {
                    LabeledContent("Card Size",
                                   value: "\(settings.columns(isLandscape: false)) / \(settings.columns(isLandscape: true))")
                }
                Toggle("Blur Covers", isOn: $settings.blurCovers)
            } header: {
                Text("Gallery List")
            } footer: {
                Text("Card size applies to the grid and waterfall styles.")
            }
            Section {
                Toggle("Hide Tab Bar When Scrolling", isOn: $settings.hideBarsOnScroll)
            } footer: {
                if UIDevice.current.userInterfaceIdiom == .pad {
                    Text("Scrolling down a list slides the tab bar away; scrolling up brings it back.")
                } else {
                    Text("Scrolling down a list shrinks the tab bar to a small button; scrolling up brings it back.")
                }
            }
            if UIDevice.current.userInterfaceIdiom == .pad {
                Section {
                    Toggle("Tab Bar Follows Your Hand", isOn: $settings.followHand)
                    if settings.followHand {
                        Picker("Follow Speed", selection: $settings.followSpeed) {
                            ForEach(FollowSpeed.allCases) { Text($0.title).tag($0) }
                        }
                    } else {
                        Picker("Tab Bar Position", selection: $settings.tabBarPosition) {
                            ForEach(TabBarPosition.allCases) { Text($0.title).tag($0) }
                        }
                    }
                } footer: {
                    Text("The tab bar moves to the side of the screen you touch: Fast at the first touch, Medium after two touches on that side, Slow after four.")
                }
            }
            TagSettingsSection(settings: settings)
        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AccentPicker: View {
    @Binding var selection: AccentChoice

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 14) {
                ForEach(AccentChoice.allCases) { choice in
                    Button {
                        selection = choice
                    } label: {
                        Circle()
                            .fill(choice.color.gradient)
                            .frame(width: 30, height: 30)
                            .overlay {
                                if choice == selection {
                                    Image(systemName: "checkmark")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(.white)
                                }
                            }
                            .padding(3)
                            .overlay {
                                Circle().strokeBorder(choice == selection ? choice.color : .clear, lineWidth: 2)
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(choice.rawValue.capitalized)
                }
            }
            .padding(.vertical, 4)
        }
        .scrollIndicators(.hidden)
    }
}

/// Tag display options and the local tag database.
private struct TagSettingsSection: View {
    @Bindable var settings: AppSettings
    @Environment(TagStore.self) private var tagStore

    var body: some View {
        Section {
            Toggle("Show Tags on Cards", isOn: $settings.showTags)
            Toggle("Translate Tags to Chinese", isOn: $settings.translateTags)
            Button {
                Task { await tagStore.update() }
            } label: {
                HStack {
                    Text("Update Tag Database")
                    Spacer()
                    if tagStore.isUpdating {
                        ProgressView()
                    } else if let date = tagStore.lastUpdated {
                        Text(date, format: .relative(presentation: .named))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .disabled(tagStore.isUpdating)
        } header: {
            Text("Tags")
        } footer: {
            Text("\(tagStore.tagCount) tags, \(tagStore.translationCount) translations (EhTagTranslation). Updated automatically every week.")
        }
    }
}

// MARK: - Card size

struct CardSizeView: View {
    @Environment(AppSettings.self) private var settings
    @State private var sync = SettingsSync.shared

    /// iPhone and iPad keep their own column counts.
    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    var body: some View {
        @Bindable var settings = settings
        Form {
            ColumnsSection(title: "Portrait", isLandscape: false, range: AppSettings.portraitColumns,
                           columns: isPad ? $settings.padColumnsPortrait : $settings.phoneColumnsPortrait)
            ColumnsSection(title: "Landscape", isLandscape: true, range: AppSettings.landscapeColumns,
                           columns: isPad ? $settings.padColumnsLandscape : $settings.phoneColumnsLandscape,
                           footer: "Choose how many columns of cards to show. Phones start with 2 in portrait and 4 in landscape, tablets with 3 and 5. A narrow window (Split View) may show fewer.")
            if sync.isEnabled {
                Section {
                    Toggle("Sync Columns", isOn: $settings.syncColumns)
                } footer: {
                    Text("The column counts sync with your other devices over iCloud. iPhone and iPad each keep their own.")
                }
            }
        }
        .navigationTitle("Card Size")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The columns for one orientation: a preview, the count and a stepper.
private struct ColumnsSection: View {
    let title: LocalizedStringKey
    let isLandscape: Bool
    let range: ClosedRange<Int>
    @Binding var columns: Int
    var footer: LocalizedStringKey? = nil

    @Environment(AppSettings.self) private var settings

    var body: some View {
        Section {
            HStack(spacing: 16) {
                MiniScreen(columns: columns, isLandscape: isLandscape, waterfall: settings.listStyle != .grid)
                    .frame(width: isLandscape ? 132 : 70, height: isLandscape ? 66 : 140)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(columns)")
                        .font(.largeTitle.bold())
                        .contentTransition(.numericText())
                    Text("columns")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Stepper(title, value: $columns, in: range)
                    .labelsHidden()
            }
            .padding(.vertical, 6)
            .animation(.smooth, value: columns)
            .sensoryFeedback(.selection, trigger: columns)
        } header: {
            Text(title)
        } footer: {
            if let footer { Text(footer) }
        }
    }
}

private struct MiniScreen: View {
    let columns: Int
    let isLandscape: Bool
    let waterfall: Bool

    var body: some View {
        Canvas { context, canvasSize in
            let frame = CGRect(origin: .zero, size: canvasSize)
            let screen = Path(roundedRect: frame.insetBy(dx: 1, dy: 1), cornerRadius: 10)
            context.fill(screen, with: .color(Color(uiColor: .systemBackground)))
            context.stroke(screen, with: .color(Color(uiColor: .separator)), lineWidth: 1)
            context.clip(to: screen)
            let pad: CGFloat = 5
            let gap: CGFloat = 3
            let n = CGFloat(max(1, columns))
            let width = (canvasSize.width - pad * 2 - gap * (n - 1)) / n
            let ratios: [CGFloat] = [1.4, 1.15, 1.55, 1.25, 1.05, 1.45]
            for column in 0..<max(1, columns) {
                var y: CGFloat = pad + 6
                var index = column
                while y < canvasSize.height {
                    let height = width * (waterfall ? ratios[index % ratios.count] : 1.33)
                    let rect = CGRect(x: pad + CGFloat(column) * (width + gap), y: y, width: width, height: height)
                    context.fill(Path(roundedRect: rect, cornerRadius: 2),
                                 with: .color(Color.accentColor.opacity(0.25 + 0.1 * Double(index % 3))))
                    y += height + gap
                    index += columns
                }
            }
        }
    }
}

// MARK: - Reading / General

struct ReadingSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        Form {
            ReadingSettingsSections(settings: settings)
        }
        .navigationTitle("Reading")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct GeneralSettingsView: View {
    @Environment(AppSettings.self) private var settings
    @State private var cacheSize: Int?
    @State private var confirmClearCache = false

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section("Home") {
                NavigationLink("Pinned Rows") { HomeRowsSettingsView() }
                Picker("Language", selection: $settings.homeLanguage) {
                    ForEach(LanguageFilter.allCases) { Text($0.title).tag($0) }
                }
                Picker("Sort", selection: $settings.homeSort) {
                    ForEach(SearchSort.allCases) { Text($0.title).tag($0) }
                }
            }
            Section {
                Picker("Language", selection: $settings.searchLanguage) {
                    ForEach(LanguageFilter.allCases) { Text($0.title).tag($0) }
                }
                Picker("Sort", selection: $settings.searchSort) {
                    ForEach(SearchSort.allCases) { Text($0.title).tag($0) }
                }
                Toggle("Save Search History", isOn: $settings.saveSearchHistory)
            } header: {
                Text("Search")
            } footer: {
                Text("Recent searches show when you start a search and sync through iCloud with your settings. When this is off, new searches aren't saved and the list is hidden.")
            }
            Section {
                Toggle("App Lock", isOn: Binding(get: { AppLock.shared.isEnabled }, set: { AppLock.shared.isEnabled = $0 }))
                    .disabled(!AppLock.shared.available)
            } footer: {
                Text("Asks for Face ID, Touch ID or your passcode when the app opens or comes back, and hides it in the app switcher.")
            }
            Section {
                Picker("In the Background", selection: $settings.backgroundPrivacy) {
                    ForEach(BackgroundPrivacy.allCases) { Text($0.title).tag($0) }
                }
            } footer: {
                Text("What the app shows when you switch away from it, so the app switcher shows nothing private.")
            }
            Section {
                Toggle("Detect Gallery Links", isOn: $settings.detectClipboard)
            } footer: {
                Text("When you return to the app, offer to open an nhentai link found on the clipboard. iOS asks for permission to paste.")
            }
            Section {
                LabeledContent("Image Cache") {
                    if let cacheSize {
                        Text(Int64(cacheSize), format: .byteCount(style: .file))
                    } else {
                        ProgressView()
                    }
                }
                Button("Clear Image Cache", role: .destructive) { confirmClearCache = true }
            } header: {
                Text("Storage")
            }
        }
        .navigationTitle("General")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refreshCacheSize() }
        .confirmationDialog("Clear the image cache?", isPresented: $confirmClearCache, titleVisibility: .visible) {
            Button("Clear Image Cache", role: .destructive) {
                ImageSetup.clearCache()
                Task { await refreshCacheSize() }
            }
        }
    }

    private func refreshCacheSize() async {
        cacheSize = await Task.detached { ImageSetup.diskUsage }.value
    }
}

// MARK: - About

struct AboutView: View {
    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(short) (\(build))"
    }

    @State private var egg = AboutEgg()

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    AboutIcon(egg: egg)
                    Text(verbatim: "AENHT").font(.title2.bold())
                    Text("Version \(version)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("A modified version of eros_n by honjow.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            Section {
                Link(destination: URL(string: "https://github.com/AtlanYuey/AENHT")!) {
                    LabeledContent("Source Code", value: "GitHub")
                }
                Link(destination: URL(string: "https://github.com/honjow/eros_n")!) {
                    LabeledContent("Original Project", value: "honjow/eros_n")
                }
                Link(destination: NHHost.site) {
                    LabeledContent("Website", value: "nhentai.net")
                }
            }
            Section {
                NavigationLink("Check for Updates") { UpdatesView() }
                Button("Share Diagnostic Log") { ShareSheet.present([DiagnosticReport.text()]) }
            } footer: {
                Text("The diagnostic log lists what went wrong in this session (failed requests, translations, sync), without tokens, cookies or keys.")
            }
            Section {
                NavigationLink {
                    AcknowledgementsView()
                } label: {
                    Text("Open Source Licenses")
                }
            }
        }
        // The easter egg flies over the whole page, not just its row.
        .overlay { AboutEggEffects(egg: egg) }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Licenses of the original project and the components the app uses.
struct AcknowledgementsView: View {
    private struct Item: Identifiable {
        let name: String
        let author: String
        let license: String
        /// License text bundled with the app, or nil for a link.
        let file: String?
        let url: URL

        var id: String { name }
    }

    private let items: [Item] = [
        Item(name: "eros_n", author: "honjow", license: "Apache License 2.0",
             file: "eros_n-LICENSE", url: URL(string: "https://github.com/honjow/eros_n")!),
        Item(name: "Nuke", author: "Alexander Grebenyuk", license: "MIT License",
             file: "Nuke-LICENSE", url: URL(string: "https://github.com/kean/Nuke")!),
        // In the shared Kotlin framework (the Apache License's text is eros_n's file).
        Item(name: "Kotlin, kotlinx.coroutines, kotlinx.serialization", author: "JetBrains", license: "Apache License 2.0",
             file: "eros_n-LICENSE", url: URL(string: "https://github.com/JetBrains/kotlin")!),
        Item(name: "Ktor", author: "JetBrains", license: "Apache License 2.0",
             file: "eros_n-LICENSE", url: URL(string: "https://github.com/ktorio/ktor")!),
        Item(name: "Room, SQLite for KMP", author: "Google", license: "Apache License 2.0",
             file: "eros_n-LICENSE", url: URL(string: "https://developer.android.com/kotlin/multiplatform/room")!),
        Item(name: "Okio", author: "Square, Inc.", license: "Apache License 2.0",
             file: "eros_n-LICENSE", url: URL(string: "https://github.com/square/okio")!),
        Item(name: "Ksoup", author: "Fleeksoft", license: "MIT License",
             file: nil, url: URL(string: "https://github.com/fleeksoft/ksoup")!),
        Item(name: "LobeHub Icons", author: "LobeHub", license: "MIT License",
             file: "LobeHub-Icons-LICENSE", url: URL(string: "https://github.com/lobehub/lobe-icons")!),
        Item(name: "Real-ESRGAN (super-resolution models)", author: "Xintao Wang", license: "BSD 3-Clause License",
             file: "Real-ESRGAN-LICENSE", url: URL(string: "https://github.com/xinntao/Real-ESRGAN")!),
        Item(name: "nhentai_tags_fetcher", author: "honjow", license: "MIT License",
             file: "nhentai_tags_fetcher-LICENSE", url: URL(string: "https://github.com/honjow/nhentai_tags_fetcher")!),
        Item(name: "EhTagTranslation Database", author: "EhTagTranslation", license: "CC BY-NC-SA 3.0",
             file: nil, url: URL(string: "https://github.com/EhTagTranslation/Database")!),
    ]

    var body: some View {
        List {
            Section {
                ForEach(items) { item in
                    if let file = item.file {
                        NavigationLink {
                            LicenseTextView(title: item.name, file: file, url: item.url)
                        } label: {
                            row(item)
                        }
                    } else {
                        Link(destination: item.url) {
                            row(item)
                        }
                        .foregroundStyle(.primary)
                    }
                }
            } footer: {
                Text("Tag translations come from the EhTagTranslation database, licensed under CC BY-NC-SA 3.0 (attribution, non-commercial, share alike). They are downloaded when the app updates its tags.")
            }
        }
        .navigationTitle("Open Source Licenses")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(_ item: Item) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: item.name)
            Text(verbatim: "\(item.author) · \(item.license)")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

private struct LicenseTextView: View {
    let title: String
    let file: String
    let url: URL

    private var text: String {
        guard let path = Bundle.main.url(forResource: file, withExtension: "txt"),
              let text = try? String(contentsOf: path, encoding: .utf8)
        else { return url.absoluteString }
        return text
    }

    var body: some View {
        ScrollView {
            Text(verbatim: text)
                .font(.footnote.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(Text(verbatim: title))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Link(destination: url) {
                    Image(systemName: "safari")
                }
            }
        }
    }
}

/// Downloads › Settings: how many at once, retries, a pause between pages, E-Hentai's originals.
struct DownloadSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section {
                Stepper("Galleries at Once: \(settings.downloadGalleries)", value: $settings.downloadGalleries, in: 1...5)
                Stepper("Pages at Once: \(settings.downloadPages)", value: $settings.downloadPages, in: 1...6)
                Stepper("Retries: \(settings.downloadRetries)", value: $settings.downloadRetries, in: 0...5)
            } footer: {
                Text("More at once is faster but more likely to be limited by the site. E-Hentai uses at most 3 pages at once.")
            }
            Section {
                VStack(alignment: .leading) {
                    LabeledContent("Interval") {
                        Text(String(format: "%.1f s", settings.downloadInterval)).monospacedDigit()
                    }
                    Slider(value: $settings.downloadInterval, in: 0...5, step: 0.5)
                }
            } footer: {
                Text("A pause before each page, gentler on the sites.")
            }
            if settings.ehentaiEnabled {
                Section {
                    Toggle("Download Original Images", isOn: $settings.ehOriginalImages)
                } header: {
                    Text(SiteKind.ehentai.displayName)
                } footer: {
                    Text("Original images are larger; on E-Hentai they cost GP from your account when the file is big.")
                }
            }
        }
        .navigationTitle("Download Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The diagnostic report: the app, the device, a few settings and the log (without secrets).
@MainActor
enum DiagnosticReport {
    static func text() -> String {
        let info = Bundle.main.infoDictionary
        let settings = AppSettings.shared
        var header = "AENHT \(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))\n"
        header += "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion), \(UIDevice.current.model)\n"
        header += "Sites: \(settings.enabledSites.map(\.rawValue).joined(separator: ", "))"
        header += " · nhentai login: \(UserSession.shared.isLoggedIn) · E-Hentai login: \(EhSession.shared.isLoggedIn)"
        header += " · ExHentai: \(SiteKind.exHentaiActive)\n"
        header += "Reader: \(settings.readMode.rawValue) · Language: \(Locale.preferredLanguages.first ?? "?")"
        return Diagnostics.shared.report(header: header)
    }
}

/// About › Check for Updates: the latest version, the latest test build, and the release notes.
struct UpdatesView: View {
    @State private var info: UpdateInfo?
    @State private var error: String?

    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    var body: some View {
        List {
            Section {
                LabeledContent("This Version", value: version)
                if let info {
                    if let latest = info.latest, let url = URL(string: latest.url) {
                        Link(destination: url) {
                            LabeledContent("Latest Version") {
                                VStack(alignment: .trailing) {
                                    Text(latest.tag)
                                    if info.updateAvailable { Text("Update available").font(.caption).foregroundStyle(.tint) }
                                }
                            }
                        }
                    } else {
                        LabeledContent("Latest Version", value: String(localized: "None yet"))
                    }
                    if let test = info.testBuild, let url = URL(string: test.url) {
                        Link(destination: url) {
                            LabeledContent("Latest Test Build", value: String(test.date.prefix(16)).replacingOccurrences(of: "T", with: " "))
                        }
                    }
                }
            }
            if let error {
                Text(error).foregroundStyle(.secondary)
            } else if let info {
                ForEach(info.releases.filter { !$0.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.prefix(10), id: \.tag) { release in
                    Section("\(release.name) · \(String(release.date.prefix(10)))") {
                        Text(release.notes.trimmingCharacters(in: .whitespacesAndNewlines)).font(.footnote)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Check for Updates")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                info = try await UpdateChecker().check(currentVersion: version)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
