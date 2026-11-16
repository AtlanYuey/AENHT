import Shared
import SwiftUI
import WebKit

// MARK: - Accounts

/// The accounts of both sites (Me › Accounts).
struct AccountsView: View {
    @Environment(UserSession.self) private var session
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @State private var book = AccountBook.shared
    @State private var showLogin = false
    @State private var showEhLogin = false
    @State private var askKey = false
    @State private var apiKey = ""
    @State private var error: String?

    var body: some View {
        List {
            if settings.nhentaiEnabled {
                Section {
                    ForEach(book.accounts(for: .nhentai)) { account in
                        savedRow(account) { AccountView() }
                    }
                    if book.accounts(for: .nhentai).isEmpty, session.isLoggedIn {
                        NavigationLink { AccountView() } label: {
                            HStack(spacing: 12) {
                                AvatarView(url: session.user?.avatar, size: 44)
                                VStack(alignment: .leading) {
                                    Text(session.user?.username ?? String(localized: "Logged In")).font(.headline)
                                    Text("nhentai account").font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    Button { showLogin = true } label: {
                        accountRow(session.isLoggedIn ? "Add Account" : "Log In", session.isLoggedIn ? nil : "Sync favorites and post comments")
                    }
                    Button("Log In with API Key") {
                        apiKey = ""
                        askKey = true
                    }
                } header: {
                    Text("nhentai")
                } footer: {
                    if book.accounts(for: .nhentai).count > 1 { Text("Tap an account to switch to it.") }
                }
            }
            if settings.ehentaiEnabled {
                Section {
                    ForEach(book.accounts(for: .ehentai)) { account in
                        savedRow(account) { EhAccountView() }
                    }
                    if book.accounts(for: .ehentai).isEmpty, ehSession.isLoggedIn {
                        NavigationLink { EhAccountView() } label: {
                            accountRow("Logged In", ehSession.hasExHentai ? "E-Hentai account · ExHentai access" : "E-Hentai account")
                        }
                    }
                    Button { showEhLogin = true } label: {
                        accountRow(ehSession.isLoggedIn ? "Add Account" : "Log In",
                                   ehSession.isLoggedIn ? nil : "Favorites, ratings, watched tags and ExHentai")
                    }
                    NavigationLink("Log In with Cookies") { EhCookieLoginView() }
                } header: {
                    Text(SiteKind.ehentai.displayName)
                } footer: {
                    if book.accounts(for: .ehentai).count > 1 { Text("Tap an account to switch to it.") }
                }
            }
        }
        .navigationTitle("Accounts")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showLogin) { LoginView() }
        .sheet(isPresented: $showEhLogin) { EhLoginView() }
        .alert("Log In with API Key", isPresented: $askKey) {
            SecureField("API Key", text: $apiKey)
            Button("Log In") {
                let key = apiKey.trimmingCharacters(in: .whitespaces)
                guard !key.isEmpty else { return }
                Task {
                    do { try await session.loginWithApiKey(key) } catch { self.error = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Create a key on nhentai.net › Settings › API Keys.")
        }
        .alert("Couldn't Log In", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    /// A saved account: the active one opens its page; another one is switched to.
    @ViewBuilder
    private func savedRow<Destination: View>(_ account: SavedAccount, @ViewBuilder destination: () -> Destination) -> some View {
        let label = HStack(spacing: 12) {
            AvatarView(url: account.avatar.flatMap(URL.init(string:)), size: 44)
            VStack(alignment: .leading) {
                Text(account.name).font(.headline).foregroundStyle(.primary)
                if book.isActive(account) { Text("Active").font(.subheadline).foregroundStyle(.secondary) }
            }
            Spacer()
            if book.isActive(account) { Image(systemName: "checkmark").foregroundStyle(.tint) }
        }
        if book.isActive(account) {
            NavigationLink { destination() } label: { label }
        } else {
            Button {
                Task {
                    if !(await book.switchTo(account)) {
                        error = String(localized: "The login couldn't be saved on this device.")
                    }
                }
            } label: { label }
            .swipeActions {
                Button("Remove from This Device", systemImage: "trash", role: .destructive) { book.forget(account) }
            }
        }
    }

    private func accountRow(_ title: LocalizedStringKey, _ subtitle: LocalizedStringKey?) -> some View {
        HStack(spacing: 12) {
            AvatarView(url: nil, size: 44)
            VStack(alignment: .leading) {
                Text(title).font(.headline).foregroundStyle(.primary)
                if let subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
            }
        }
    }
}

struct EhAccountView: View {
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss
    @State private var confirmLogout = false
    @State private var checking = false
    @State private var limit: EhImageLimit?

    var body: some View {
        @Bindable var settings = settings
        List {
            Section {
                LabeledContent("Member ID", value: EhCredentials.shared.memberId ?? "-")
                LabeledContent("ExHentai", value: ehSession.hasExHentai ? String(localized: "Available") : String(localized: "Not available"))
            }
            Section {
                Toggle("Use ExHentai", isOn: $settings.useExHentai)
                    .disabled(!ehSession.hasExHentai)
                Button("Check Access Again") {
                    checking = true
                    Task {
                        _ = try? await EhService.shared.api.verifyLogin()
                        ehSession.refresh()
                        checking = false
                    }
                }
                .disabled(checking)
            } footer: {
                Text("ExHentai shows galleries that E-Hentai hides. It needs an account that has been given access.")
            }
            Section {
                if let limit {
                    VStack(alignment: .leading, spacing: 8) {
                        LabeledContent("Used", value: "\(limit.used) / \(limit.limit)")
                        ProgressView(value: Double(limit.used), total: Double(max(1, limit.limit)))
                        if let cost = limit.resetCost {
                            Text("Reset Cost: \(Int(truncating: cost)) GP").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } else {
                    LabeledContent("Used") { ProgressView() }
                }
            } header: {
                Text("Image Limits")
            } footer: {
                Text("Viewing and downloading pages counts toward this limit; it recovers over time.")
            }
            Section {
                NavigationLink("E-Hentai Settings") { EhWebPage(path: "/uconfig.php", title: "E-Hentai Settings") }
                NavigationLink("My Tags") { EhWebPage(path: "/mytags", title: "My Tags") }
            } footer: {
                Text("The site's own pages, signed in with this account.")
            }
            Section {
                Button("Log Out", role: .destructive) { confirmLogout = true }
            }
        }
        .navigationTitle("E-Hentai")
        .navigationBarTitleDisplayMode(.inline)
        .task { limit = try? await EhService.shared.imageLimit() }
        .confirmationDialog("Log out of E-Hentai?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Log Out", role: .destructive) {
                ehSession.logout()
                dismiss()
            }
        }
    }
}

/// Logs in on the E-Hentai forums in a web view; the login cookies are read
/// from it and checked with the site.
struct EhLoginView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isLoading = true
    @State private var failed = false

    var body: some View {
        NavigationStack {
            EhLoginWebView(isLoading: $isLoading) { member, hash in
                Task {
                    if await EhSession.shared.login(memberId: member, passHash: hash, igneous: nil) {
                        dismiss()
                    } else {
                        failed = true
                    }
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle("Log In")
            .navigationBarTitleDisplayMode(.inline)
            .barToolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    if isLoading { ProgressView() }
                }
            }
            .alert("Couldn't Log In", isPresented: $failed) {
                Button("OK", role: .cancel) {}
            } message: {
                if let reason = EhSession.shared.loginError { Text(verbatim: reason) }
                else { Text("E-Hentai didn't accept the login. Try again, or log in with cookies.") }
            }
        }
    }
}

struct EhLoginWebView: UIViewRepresentable {
    @Binding var isLoading: Bool
    let onCookies: (String, String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        configuration.websiteDataStore.httpCookieStore.add(context.coordinator)
        context.coordinator.cookieStore = configuration.websiteDataStore.httpCookieStore
        webView.load(URLRequest(url: URL(string: "https://forums.e-hentai.org/index.php?act=Login&CODE=00")!))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.cookieStore?.remove(coordinator)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        var parent: EhLoginWebView
        weak var cookieStore: WKHTTPCookieStore?
        private var done = false

        init(_ parent: EhLoginWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            parent.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.isLoading = false
            check(webView.configuration.websiteDataStore.httpCookieStore)
        }

        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
            check(cookieStore)
        }

        private func check(_ store: WKHTTPCookieStore) {
            guard !done else { return }
            store.getAllCookies { cookies in
                let ours = cookies.filter { $0.domain.hasSuffix("e-hentai.org") }
                guard let member = ours.first(where: { $0.name == "ipb_member_id" })?.value, !member.isEmpty, member != "0",
                      let hash = ours.first(where: { $0.name == "ipb_pass_hash" })?.value, !hash.isEmpty,
                      !self.done else { return }
                self.done = true
                DispatchQueue.main.async { self.parent.onCookies(member, hash) }
            }
        }
    }
}

/// Logs in with the cookies of a browser where the user is logged in.
struct EhCookieLoginView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var memberId = ""
    @State private var passHash = ""
    @State private var igneous = ""
    @State private var working = false
    @State private var failed = false

    var body: some View {
        Form {
            Section {
                TextField("ipb_member_id", text: $memberId)
                TextField("ipb_pass_hash", text: $passHash)
                TextField("igneous", text: $igneous)
            } footer: {
                Text("Copy these cookies of e-hentai.org from a browser where you are logged in. igneous is only needed for ExHentai, and is usually picked up by itself.")
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .fontDesign(.monospaced)
            Section {
                Button("Log In") {
                    working = true
                    Task {
                        let ok = await EhSession.shared.login(memberId: memberId, passHash: passHash, igneous: igneous)
                        working = false
                        if ok { dismiss() } else { failed = true }
                    }
                }
                .disabled(working || memberId.isEmpty || passHash.isEmpty)
            }
        }
        .navigationTitle("Log In with Cookies")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Couldn't Log In", isPresented: $failed) {
            Button("OK", role: .cancel) {}
        } message: {
            if let reason = EhSession.shared.loginError { Text(verbatim: reason) }
            else { Text("E-Hentai didn't accept these cookies.") }
        }
    }
}

// MARK: - Sites settings

/// Me › Sites: which sites are used and what each screen opens on.
struct SitesSettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(EhSession.self) private var ehSession

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section {
                Toggle("nhentai", isOn: Binding(get: { settings.nhentaiEnabled },
                                                set: { if $0 || settings.ehentaiEnabled { settings.nhentaiEnabled = $0 } }))
                Toggle("E-Hentai", isOn: Binding(get: { settings.ehentaiEnabled },
                                                 set: { if $0 || settings.nhentaiEnabled { settings.ehentaiEnabled = $0 } }))
                // Right under E-Hentai, while it's on.
                if settings.ehentaiEnabled {
                    Toggle(isOn: $settings.useExHentai) {
                        VStack(alignment: .leading) {
                            Text("Use ExHentai")
                            if !ehSession.hasExHentai {
                                Text("Needs an account with ExHentai access").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(!ehSession.hasExHentai)
                }
            } header: {
                Text("Sites in Use")
            } footer: {
                Text("A site that is turned off disappears everywhere in the app. At least one stays on.")
            }
            if settings.nhentaiEnabled, settings.ehentaiEnabled {
                Section {
                    Picker("Home", selection: $settings.homeSiteDefault) {
                        Text("Last Used").tag(SiteChoice.follow)
                        Text("nhentai").tag(SiteChoice.nh)
                        Text("E-Hentai").tag(SiteChoice.eh)
                    }
                    Picker("Search", selection: $settings.searchSite) {
                        ForEach(SiteChoice.allCases.filter { $0 != .all }) { Text($0.title).tag($0) }
                    }
                    Picker("Favorites", selection: $settings.favoritesSite) {
                        ForEach(SiteChoice.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("History", selection: $settings.historySite) {
                        ForEach(SiteChoice.allCases) { Text($0.title).tag($0) }
                    }
                    Picker("Downloads", selection: $settings.downloadsSite) {
                        ForEach(SiteChoice.allCases) { Text($0.title).tag($0) }
                    }
                } header: {
                    Text("Show When Opened")
                } footer: {
                    Text("“Same as Home” shows the site the home page is on.")
                }
                Section {
                    Toggle("Search Both Sites", isOn: $settings.searchBothSites)
                } footer: {
                    Text("Searches both sites at once. E-Hentai pauses networks that send too many requests, so this is off by default.")
                }
            }
            if settings.ehentaiEnabled {
                Section("E-Hentai") {
                    NavigationLink {
                        if ehSession.isLoggedIn { EhAccountView() } else { EhCookieLoginView() }
                    } label: {
                        LabeledContent("Account", value: ehSession.isLoggedIn ? String(localized: "Logged In") : String(localized: "Not logged in"))
                    }
                }
            }
        }
        .navigationTitle("Sites")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Filters

/// Me › Filters: rules that hide galleries of both sites from lists.
struct FiltersSettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(UserSession.self) private var session
    @State private var filters = Filters.shared
    @State private var pattern = ""
    @State private var target = "tag"
    @State private var regex = false

    private func title(_ target: String) -> LocalizedStringKey {
        switch target {
        case "tag": "Tag"
        case "title": "Title"
        case "comment": "Comment"
        case "commenter": "Commenter"
        default: "Uploader"
        }
    }

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section {
                Picker("Match", selection: $target) {
                    ForEach(["tag", "title", "uploader", "comment", "commenter"], id: \.self) { Text(title($0)).tag($0) }
                }
                TextField("", text: $pattern)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Toggle("Regular Expression", isOn: $regex)
                Button("Add Rule") {
                    filters.add(target: target, pattern: pattern, regex: regex)
                    pattern = ""
                }
                .disabled(pattern.trimmingCharacters(in: .whitespaces).isEmpty)
            } footer: {
                Text("Galleries that match a rule are left out of Home and search results on both sites. A tag rule matches the tag in any namespace (nhentai's tag: and E-Hentai's female:, male:, …).")
            }
            let rules = filters.rules
            if !rules.isEmpty {
                Section("Rules") {
                    ForEach(rules, id: \.id) { rule in
                        Toggle(isOn: Binding(get: { rule.enabled }, set: { filters.setEnabled(rule, $0) })) {
                            VStack(alignment: .leading) {
                                Text(rule.pattern)
                                Text(title(rule.target)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button("Delete", systemImage: "trash", role: .destructive) { filters.remove(rule) }
                        }
                    }
                }
            }
            if settings.nhentaiEnabled {
                Section {
                    Toggle(isOn: $settings.useNhBlacklist) {
                        VStack(alignment: .leading) {
                            Text("nhentai Account Blacklist")
                            Text(session.isLoggedIn ? "\(filters.nhBlacklist.count) tags" : "Not logged in")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Uses the tag blacklist of your nhentai account (nhentai.net › Settings › Blacklist).")
                }
            }
            let blocked = settings.blockedCommenterSet.sorted()
            if !blocked.isEmpty {
                Section {
                    ForEach(blocked, id: \.self) { name in
                        HStack {
                            Text(name)
                            Spacer()
                            Button("Unblock") { settings.unblockCommenter(name) }
                        }
                    }
                } header: {
                    Text("Blocked Commenters")
                } footer: {
                    Text("Their comments are hidden on E-Hentai galleries. Long-press a comment to block its author.")
                }
            }
        }
        .navigationTitle("Filters")
        .navigationBarTitleDisplayMode(.inline)
        .task { await filters.refreshNhBlacklist(force: true) }
    }
}

/// Settings › General › Home › Pinned Rows: up to two rows of covers above each site's feed.
struct HomeRowsSettingsView: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        Form {
            ForEach(settings.enabledSites) { site in
                let rows = settings.homeRows(for: site)
                Section {
                    Picker("First Row", selection: Binding<HomeRow?>(
                        get: { rows.first },
                        set: { new in
                            guard let new else { settings.setHomeRows([], for: site); return }
                            settings.setHomeRows([new] + rows.dropFirst().filter { $0 != new }, for: site)
                        }
                    )) {
                        Text("None").tag(HomeRow?.none)
                        ForEach(HomeRow.rows(for: site)) { Text($0.title).tag(HomeRow?.some($0)) }
                    }
                    if let first = rows.first {
                        Picker("Second Row", selection: Binding<HomeRow?>(
                            get: { rows.dropFirst().first },
                            set: { new in settings.setHomeRows([first, new].compactMap { $0 }, for: site) }
                        )) {
                            Text("None").tag(HomeRow?.none)
                            ForEach(HomeRow.rows(for: site).filter { $0 != first }) { Text($0.title).tag(HomeRow?.some($0)) }
                        }
                    }
                } header: {
                    Text(site.displayName)
                } footer: {
                    Text("Rows of covers above the feed, at most two. They load with the feed and on refresh.")
                }
            }
        }
        .navigationTitle("Pinned Rows")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// An E-Hentai page (site settings, My Tags) in a web view, signed in with
/// the account's cookies; ExHentai's when it is in use.
struct EhWebPage: View {
    let path: String
    let title: LocalizedStringKey

    var body: some View {
        EhSignedWebView(url: URL(string: (SiteKind.exHentaiActive ? "https://exhentai.org" : "https://e-hentai.org") + path)!)
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

private struct EhSignedWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView(frame: .zero)
        let store = web.configuration.websiteDataStore.httpCookieStore
        let credentials = EhCredentials.shared
        var cookies: [HTTPCookie] = []
        for domain in [".e-hentai.org", ".exhentai.org"] {
            var values: [(String, String)] = []
            if let id = credentials.memberId { values.append(("ipb_member_id", id)) }
            if let hash = credentials.passHash { values.append(("ipb_pass_hash", hash)) }
            if domain == ".exhentai.org", let igneous = credentials.igneous, !igneous.isEmpty, igneous != "mystery" {
                values.append(("igneous", igneous))
            }
            for (name, value) in values {
                if let cookie = HTTPCookie(properties: [.domain: domain, .path: "/", .name: name, .value: value, .secure: "TRUE"]) {
                    cookies.append(cookie)
                }
            }
        }
        let group = DispatchGroup()
        for cookie in cookies {
            group.enter()
            store.setCookie(cookie) { group.leave() }
        }
        group.notify(queue: .main) { web.load(URLRequest(url: url)) }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}
}
