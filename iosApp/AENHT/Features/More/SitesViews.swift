import SwiftUI
import WebKit

// MARK: - Accounts

/// The accounts of both sites (Me › Accounts).
struct AccountsView: View {
    @Environment(UserSession.self) private var session
    @Environment(EhSession.self) private var ehSession
    @Environment(AppSettings.self) private var settings
    @State private var showLogin = false
    @State private var showEhLogin = false

    var body: some View {
        List {
            if settings.nhentaiEnabled {
                Section("nhentai") {
                    if session.isLoggedIn {
                        NavigationLink { AccountView() } label: {
                            HStack(spacing: 12) {
                                AvatarView(url: session.user?.avatar, size: 44)
                                VStack(alignment: .leading) {
                                    Text(session.user?.username ?? String(localized: "Logged In")).font(.headline)
                                    Text("nhentai account").font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    } else {
                        Button { showLogin = true } label: {
                            accountRow("Log In", "Sync favorites and post comments")
                        }
                    }
                }
            }
            if settings.ehentaiEnabled {
                Section("E-Hentai") {
                    if ehSession.isLoggedIn {
                        NavigationLink { EhAccountView() } label: {
                            accountRow("Logged In", ehSession.hasExHentai ? "E-Hentai account · ExHentai access" : "E-Hentai account")
                        }
                    } else {
                        Button { showEhLogin = true } label: {
                            accountRow("Log In", "Favorites, ratings, watched tags and ExHentai")
                        }
                        NavigationLink("Log In with Cookies") { EhCookieLoginView() }
                    }
                }
            }
        }
        .navigationTitle("Accounts")
        .sheet(isPresented: $showLogin) { LoginView() }
        .sheet(isPresented: $showEhLogin) { EhLoginView() }
    }

    private func accountRow(_ title: LocalizedStringKey, _ subtitle: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            AvatarView(url: nil, size: 44)
            VStack(alignment: .leading) {
                Text(title).font(.headline).foregroundStyle(.primary)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
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
                Button("Log Out", role: .destructive) { confirmLogout = true }
            }
        }
        .navigationTitle("E-Hentai")
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
            .toolbar {
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
                Text("E-Hentai didn't accept the login. Try again, or log in with cookies.")
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
        .alert("Couldn't Log In", isPresented: $failed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("E-Hentai didn't accept these cookies.")
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
            }
        }
        .navigationTitle("Sites")
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
        default: "Uploader"
        }
    }

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section {
                Picker("Match", selection: $target) {
                    ForEach(["tag", "title", "uploader"], id: \.self) { Text(title($0)).tag($0) }
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
        }
        .navigationTitle("Filters")
        .task { await filters.refreshNhBlacklist(force: true) }
    }
}
