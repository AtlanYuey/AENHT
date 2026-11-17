import SwiftUI
import WebKit

/// Signs in on nhentai.net in a web view (the site handles Turnstile and
/// its proof of work); the API tokens are read from the cookies it sets.
struct LoginView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(UserSession.self) private var session
    @State private var isLoading = true
    @State private var notSaved = false

    var body: some View {
        NavigationStack {
            LoginWebView(isLoading: $isLoading) { access, refresh in
                Task {
                    if await session.login(access: access, refresh: refresh) { dismiss() } else { notSaved = true }
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .alert("Couldn't Log In", isPresented: $notSaved) {
                Button("OK", role: .cancel) { dismiss() }
            } message: {
                Text("The login couldn't be saved on this device.")
            }
            .navigationTitle("Log In")
            .navigationBarTitleDisplayMode(.inline)
            .barToolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", systemImage: "xmark") { dismiss() }
                }
                if isLoading {
                    ToolbarItem(placement: .confirmationAction) {
                        ProgressView()
                    }
                }
            }
        }
    }
}

struct LoginWebView: UIViewRepresentable {
    @Binding var isLoading: Bool
    let onToken: (String, String?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        configuration.websiteDataStore.httpCookieStore.add(context.coordinator)
        context.coordinator.cookieStore = configuration.websiteDataStore.httpCookieStore
        // The login there was (logged out, or the account being added to) isn't
        // picked up again: the page asks for an account.
        let coordinator = context.coordinator
        Task { @MainActor in
            await WebLogins.forget(WebLogins.nhentai)
            coordinator.ready = true
            webView.load(URLRequest(url: NHHost.site.appendingPathComponent("login/")))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.cookieStore?.remove(coordinator)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        var parent: LoginWebView
        weak var cookieStore: WKHTTPCookieStore?
        /// The old login cookies are gone: cookies seen from now on are the new login's.
        var ready = false
        private var done = false

        init(_ parent: LoginWebView) {
            self.parent = parent
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            parent.isLoading = true
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.isLoading = false
            check(webView.configuration.websiteDataStore.httpCookieStore)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            parent.isLoading = false
        }

        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
            check(cookieStore)
        }

        private func check(_ store: WKHTTPCookieStore) {
            guard ready else { return }
            store.getAllCookies { [weak self] cookies in
                guard let self, !self.done else { return }
                let site = cookies.filter { $0.domain.hasSuffix("nhentai.net") }
                guard let access = site.first(where: { $0.name == "access_token" })?.value,
                      !access.isEmpty
                else { return }
                let refresh = site.first(where: { $0.name == "refresh_token" })?.value
                self.done = true
                self.parent.onToken(access.removingPercentEncoding ?? access,
                                    refresh?.removingPercentEncoding ?? refresh)
            }
        }
    }
}

/// The login cookies the web views keep for a site. The login pages start
/// without them (else the site signs straight back in to the account there
/// was), and a logout removes them. Cloudflare's own cookies stay.
enum WebLogins {
    static let nhentai = ["nhentai.net"]
    static let ehentai = ["e-hentai.org", "exhentai.org"]

    @MainActor
    static func forget(_ domains: [String]) async {
        let store = WKWebsiteDataStore.default().httpCookieStore
        for cookie in await store.allCookies()
        where domains.contains(where: { cookie.domain.hasSuffix($0) })
            && !cookie.name.hasPrefix("cf_") && !cookie.name.hasPrefix("__cf") {
            await store.deleteCookie(cookie)
        }
    }
}
