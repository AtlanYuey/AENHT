import Foundation
import Nuke
import NukeUI
import Observation
import Shared
import SwiftUI
import UIKit

// MARK: - Sites

/// The two sites the app reads from.
enum SiteKind: String, CaseIterable, Identifiable, Codable {
    case nhentai = "nh"
    case ehentai = "eh"

    var id: String { rawValue }
    /// ExHentai is in use: E-Hentai shows under ExHentai's name everywhere.
    @MainActor static var exHentaiActive: Bool { AppSettings.shared.useExHentai && EhSession.shared.hasExHentai }

    @MainActor var badge: String { self == .nhentai ? "NH" : Self.exHentaiActive ? "ExH" : "EH" }
    @MainActor var displayName: String { self == .nhentai ? "nhentai" : Self.exHentaiActive ? "ExHentai" : "E-Hentai" }

    var kotlin: Site { self == .nhentai ? Bridge.shared.nhentai : Bridge.shared.ehentai }
}

/// Which site a screen shows: a fixed one, both, or the one Home is on.
enum SiteChoice: String, CaseIterable, Identifiable {
    case follow, nh, eh, all

    var id: String { rawValue }

    @MainActor var title: LocalizedStringKey {
        switch self {
        case .follow: "Same as Home"
        case .nh: "nhentai"
        case .eh: LocalizedStringKey(SiteKind.ehentai.displayName)
        case .all: "All"
        }
    }
}

/// What the E-Hentai home page lists.
enum EhHomeList: String, CaseIterable, Identifiable {
    case latest, popular, watched
    case topDay = "top-day", topMonth = "top-month", topYear = "top-year", topAll = "top-all"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .latest: String(localized: "Latest")
        case .popular: String(localized: "Popular Now")
        case .watched: String(localized: "Watched")
        case .topDay: String(localized: "Top Galleries Yesterday")
        case .topMonth: String(localized: "Top Galleries This Month")
        case .topYear: String(localized: "Top Galleries This Year")
        case .topAll: String(localized: "Top Galleries of All Time")
        }
    }

    func source(language: LanguageFilter) -> EhListSource {
        let lang = language.queryTerm ?? ""
        switch self {
        case .latest: return Bridge.shared.search(query: lang, categoriesShown: 0)
        case .popular: return Bridge.shared.popular()
        case .watched: return Bridge.shared.watched(query: lang)
        case .topDay: return Bridge.shared.toplist(period: 0)
        case .topMonth: return Bridge.shared.toplist(period: 1)
        case .topYear: return Bridge.shared.toplist(period: 2)
        case .topAll: return Bridge.shared.toplist(period: 3)
        }
    }
}

/// An E-Hentai category's name and color (from the shared module).
struct CategoryInfo: Hashable {
    let label: String
    let color: UInt32

    init(_ category: EhCategory) {
        label = category.label
        color = UInt32(truncatingIfNeeded: category.color)
    }

    var swiftColor: Color {
        Color(red: Double((color >> 16) & 0xFF) / 255, green: Double((color >> 8) & 0xFF) / 255, blue: Double(color & 0xFF) / 255)
    }
}

// MARK: - E-Hentai login

/// The E-Hentai login cookies, in the Keychain.
final class EhCredentials: NSObject, EhCredentialStorage, @unchecked Sendable {
    static let shared = EhCredentials()

    var memberId: String? { Keychain.read("eh_member_id") }
    var passHash: String? { Keychain.read("eh_pass_hash") }
    var igneous: String? { Keychain.read("eh_igneous") }

    /// Read, compare and write in one step (the shared module saves from its own threads).
    private let lock = NSLock()

    func save(memberId: String, passHash: String, igneous: String?) -> Bool {
        store(memberId: memberId, passHash: passHash, igneous: igneous)
    }

    /// Saves a login (all three cookies or none); false when it couldn't be saved, and the one there was stays.
    @discardableResult
    func store(memberId: String, passHash: String, igneous: String?) -> Bool {
        let saved = lock.withLock {
            Keychain.writeAll([("eh_member_id", memberId), ("eh_pass_hash", passHash), ("eh_igneous", igneous)])
        }
        Task { @MainActor in EhSession.shared.refresh() }
        return saved
    }

    func updateIgneous(memberId: String, igneous: String) -> Bool {
        let saved = lock.withLock {
            self.memberId == memberId && Keychain.writeAll([("eh_igneous", igneous)])
        }
        if saved { Task { @MainActor in EhSession.shared.refresh() } }
        return saved
    }

    @discardableResult
    func clearLogin() -> Bool {
        let saved = lock.withLock { Keychain.deleteAll(["eh_member_id", "eh_pass_hash", "eh_igneous"]) }
        Task { @MainActor in EhSession.shared.refresh() }
        return saved
    }

    func clear() { _ = clearLogin() }
}

/// E-Hentai account state, observed by the UI.
@MainActor
@Observable
final class EhSession {
    static let shared = EhSession()

    private(set) var isLoggedIn = false
    private(set) var hasExHentai = false
    /// The user's favorite folder names (loaded once).
    private(set) var folderNames: [String] = (0..<10).map { "Favorites \($0)" }
    @ObservationIgnored private var foldersLoaded = false

    private init() { refresh() }

    func refresh() {
        let c = EhCredentials.shared
        isLoggedIn = !(c.memberId ?? "").isEmpty && !(c.passHash ?? "").isEmpty
        hasExHentai = isLoggedIn && !(c.igneous ?? "").isEmpty && c.igneous != "mystery"
    }

    /// Why the last `login` failed when the site couldn't be reached (nil: the site refused the cookies).
    private(set) var loginError: String?
    var storageError: String?

    /// Checks cookies (from the login page or typed) with the site and logs
    /// in with them. When they are refused, or the site can't be reached,
    /// the login there was before stays.
    func login(memberId: String, passHash: String, igneous: String?) async -> Bool {
        // One login checked at a time: the next waits for this one.
        let previous = loginTask
        let task = Task { @MainActor in
            _ = await previous?.value
            return await self.check(memberId: memberId, passHash: passHash, igneous: igneous)
        }
        loginTask = task
        return await task.value
    }

    @ObservationIgnored private var loginTask: Task<Bool, Never>?

    /// Counts the changes of login (log in, log out, switch): a check of an earlier one doesn't decide the current one.
    @ObservationIgnored private(set) var generation = 0

    /// Another login was put in place (switching accounts).
    func loginReplaced() { generation += 1 }

    private func check(memberId: String, passHash: String, igneous: String?) async -> Bool {
        generation += 1
        let mine = generation
        loginError = nil
        let member = memberId.trimmingCharacters(in: .whitespaces)
        let hash = passHash.trimmingCharacters(in: .whitespaces)
        let given = igneous?.trimmingCharacters(in: .whitespaces)
        // Checked on its own: the saved login isn't touched while the site answers.
        let check: EhLoginCheck
        do {
            check = try await EhService.shared.api.checkLogin(memberId: member, passHash: hash,
                                                             igneous: (given?.isEmpty ?? true) ? nil : given)
        } catch {
            if mine == generation { loginError = EhService.map(error).localizedDescription }
            return false
        }
        // The login changed while this one was checked: that one stays, this one isn't used.
        guard mine == generation, check.ok else { return false }
        guard EhCredentials.shared.store(memberId: member, passHash: hash, igneous: check.igneous) else {
            loginError = String(localized: "The login couldn't be saved on this device.")
            return false
        }
        refresh()
        AccountBook.shared.rememberEh()
        await FavoritesStore.shared.accountChanged(.ehentai)
        return true
    }

    @discardableResult
    func logout() -> Bool {
        let active = AccountBook.shared.accounts(for: .ehentai).first { AccountBook.shared.isActive($0) }
        guard EhCredentials.shared.clearLogin() else {
            storageError = String(localized: "The logout couldn't be saved. Your account is still signed in.")
            return false
        }
        generation += 1
        if let active { AccountBook.shared.forget(active) }
        EhService.shared.api.logout()
        AppSettings.shared.useExHentai = false
        refresh()
        Task { await FavoritesStore.shared.accountChanged(.ehentai) }
        return true
    }

    /// Another account: its folders are read again.
    func resetFolders() {
        foldersLoaded = false
        folderNames = (0..<10).map { "Favorites \($0)" }
    }

    func loadFolders() async {
        guard isLoggedIn, !foldersLoaded else { return }
        if let names = try? await EhService.shared.api.favoriteFolderNames() {
            folderNames = names
            foldersLoaded = true
        }
    }

    static let folderColors: [Color] = [
        .black, .red, .orange, .yellow, .green, Color(red: 0.6, green: 1, blue: 0.27),
        Color(red: 0.27, green: 0.73, blue: 1), .blue, .purple, .pink,
    ]
}

// MARK: - E-Hentai service

enum EhError: LocalizedError {
    case failed(String)

    var errorDescription: String? {
        if case let .failed(message) = self { return message }
        return nil
    }
}

/// E-Hentai through the shared Kotlin client, with Swift values and localized errors.
final class EhService: @unchecked Sendable {
    static let shared = EhService()

    let api = EhApi(credentials: EhCredentials.shared)

    /// Runs a shared call with the current ExHentai setting; Kotlin exceptions become `EhError`.
    func call<T>(_ block: (EhApi) async throws -> T) async throws -> T {
        api.useExHentai = await MainActor.run { AppSettings.shared.useExHentai }
        do {
            return try await block(api)
        } catch {
            throw Self.map(error)
        }
    }

    static func map(_ error: Error) -> Error {
        guard let exception = (error as NSError).userInfo["KotlinException"] as? EhException else { return error }
        let message: String = switch exception.kind {
        case "banned": String(localized: "E-Hentai has paused access from this network for a while (too many requests). Try again later.")
        case "noAccess": String(localized: "This account can't open ExHentai. Turn off ExHentai in Settings › E-Hentai.")
        case "notFound": String(localized: "This gallery isn't available.")
        case "notLoggedIn": String(localized: "Log in to your E-Hentai account first.")
        case "limitReached": String(localized: "The image limit of this account has been reached. Try again later.")
        case "http": String(localized: "Request failed (\(Int(exception.code)))")
        case "network", "rejected": exception.message ?? String(localized: "Unexpected response from the server")
        default: String(localized: "Unexpected response from the server")
        }
        return EhError.failed(message)
    }

    func list(_ source: EhListSource, cursor: String?) async throws -> FeedPage {
        let page = try await call { try await $0.list(source: source, cursor: cursor) }
        let next: String?
        if Bridge.shared.isToplist(source: source) {
            let current = Int(cursor ?? "0") ?? 0
            let count = page.pageCount.map { Int(truncating: $0) }
            next = !page.galleries.isEmpty && (count == nil || current + 1 < count!) ? "\(current + 1)" : nil
        } else {
            next = page.nextCursor
        }
        return FeedPage(items: page.galleries.map(GalleryItem.init(eh:)), next: next, total: nil)
    }

    func gallery(gid: Int, token: String) async throws -> EhGalleryDetail {
        try await call { try await $0.gallery(gid: Int64(gid), token: token) }
    }

    func thumbnails(gid: Int, token: String, page: Int) async throws -> [EhThumbnail] {
        try await call { try await $0.thumbnails(gid: Int64(gid), token: token, page: Int32(page)) }
    }

    func setFavorite(gid: Int, token: String, folder: Int?, note: String = "") async throws {
        try await call { try await $0.setFavorite(gid: Int64(gid), token: token, folder: folder.map { KotlinInt(int: Int32($0)) }, note: note) }
    }

    func favoriteNote(gid: Int, token: String) async throws -> String {
        try await call { try await $0.favoriteNote(gid: Int64(gid), token: token) }
    }

    func archiveInfo(_ d: EhGalleryDetail) async throws -> EhArchiveInfo {
        try await call { try await $0.archiveInfo(gid: d.gid, token: d.token, key: d.archiverKey ?? "") }
    }

    func requestArchive(_ d: EhGalleryDetail, option: EhArchiveOption) async throws -> String {
        try await call { try await $0.requestArchive(gid: d.gid, token: d.token, key: d.archiverKey ?? "", option: option) }
    }

    /// Votes tags up or down (adding ones not there yet); answers the site's error, or nil.
    func voteTags(_ d: EhGalleryDetail, tags: String, up: Bool) async throws -> String? {
        try await call { try await $0.voteTags(gid: d.gid, token: d.token, apiUid: d.apiUid, apiKey: d.apiKey, tags: tags, up: up) }
    }

    func torrents(gid: Int, token: String) async throws -> [EhTorrent] {
        try await call { try await $0.torrents(gid: Int64(gid), token: token) }
    }

    func imageLimit() async throws -> EhImageLimit? {
        try await call { try await $0.imageLimit() }
    }

    func voteComment(_ d: EhGalleryDetail, commentId: Int64, up: Bool) async throws -> Int? {
        try await call {
            try await $0.voteComment(gid: d.gid, token: d.token, apiUid: d.apiUid, apiKey: d.apiKey, commentId: commentId, up: up)
        }?.intValue
    }

    func postComment(gid: Int64, token: String, text: String) async throws -> [EhComment] {
        try await call { try await $0.postComment(gid: gid, token: token, text: text) }
    }

    func rate(_ detail: EhGalleryDetail, stars: Double) async throws -> Double {
        try await call { try await $0.rate(gid: detail.gid, token: detail.token, apiUid: detail.apiUid, apiKey: detail.apiKey, stars: stars) }.doubleValue
    }

    func info(gid: Int, token: String) async throws -> GalleryItem? {
        try await call { try await $0.galleryInfo(gid: Int64(gid), token: token) }.map(GalleryItem.init(eh:))
    }

    func token(gid: Int, pageToken: String, page: Int) async throws -> String {
        try await call { try await $0.galleryToken(gid: Int64(gid), pageToken: pageToken, page: Int32(page)) }
    }
}

/// One page of a feed; [next] is the cursor of the next page (nil: no more).
struct FeedPage {
    let items: [GalleryItem]
    let next: String?
    let total: Int?
}

extension GalleryItem {
    /// An E-Hentai list entry as a card.
    init(eh g: EhGallerySummary) {
        self.init(id: Int(g.gid), mediaID: "", title: g.title, thumbnail: nil,
                  thumbnailWidth: Int(g.thumbWidth), thumbnailHeight: Int(g.thumbHeight), numPages: Int(g.pages))
        site = .ehentai
        token = g.token
        absoluteThumb = g.thumbnail
        category = g.category.map(CategoryInfo.init)
        rating = g.rating
        uploader = g.uploader
        ehTags = g.tags.filter { !$0.full.hasPrefix("language:") }.map(\.name)
        // E-Hentai galleries without a language tag are Japanese.
        ehLanguage = g.language ?? (g.category?.label == "Western" ? nil : "japanese")
        favoriteFolder = g.favoriteFolder.map { Int(truncating: $0) }
    }

    /// The card of a loaded E-Hentai gallery (history, reader, downloads).
    init(ehDetail d: EhGalleryDetail, preview: GalleryItem?) {
        self.init(id: Int(d.gid), mediaID: "", title: d.title, thumbnail: nil,
                  thumbnailWidth: preview?.thumbnailWidth ?? 0, thumbnailHeight: preview?.thumbnailHeight ?? 0,
                  numPages: Int(d.pages))
        site = .ehentai
        token = d.token
        absoluteThumb = preview?.absoluteThumb ?? d.cover
        category = d.category.map(CategoryInfo.init)
        rating = d.rating
        uploader = d.uploader
        ehLanguage = d.language?.split(separator: " ").first.map { $0.lowercased() } ?? "japanese"
    }
}

// MARK: - E-Hentai pages in the reader

/// The reader's pages of E-Hentai galleries use `ehpage://<gid>/<token>/<page>`
/// URLs: this loader looks up the page's image (page links from the
/// thumbnail pages, another node when an image fails) and loads it. Nuke's
/// disk cache keeps the images under these URLs, so a page read again costs
/// no request and no image quota.
final class EhPageDataLoader: DataLoading, @unchecked Sendable {
    private let base = DataLoader()
    private var directory: EhPageDirectory { EhPageDirectory.shared }

    static func url(gid: Int, token: String, page: Int) -> URL {
        URL(string: "ehpage://\(gid)/\(token)/\(page)")!
    }

    func loadData(with request: URLRequest,
                  didReceiveData: @escaping (Data, URLResponse) -> Void,
                  completion: @escaping (Error?) -> Void) -> any Cancellable {
        guard let url = request.url, url.scheme == "ehpage" else {
            // Covers and thumbnails on ExHentai's servers need the account's cookies.
            if let url = request.url, request.value(forHTTPHeaderField: "Cookie") == nil,
               let cookies = EhService.shared.api.imageCookieHeader(url: url.absoluteString) {
                var signed = request
                signed.setValue(cookies, forHTTPHeaderField: "Cookie")
                return base.loadData(with: signed, didReceiveData: didReceiveData, completion: completion)
            }
            return base.loadData(with: request, didReceiveData: didReceiveData, completion: completion)
        }
        let task = Task {
            do {
                let parts = url.pathComponents.filter { $0 != "/" }
                guard let gid = Int(url.host() ?? ""), parts.count == 2, let page = Int(parts[1]) else {
                    throw EhError.failed(String(localized: "This page isn't available."))
                }
                let data = try await directory.image(gid: gid, token: parts[0], page: page)
                didReceiveData(data, URLResponse(url: url, mimeType: nil, expectedContentLength: data.count, textEncodingName: nil))
                completion(nil)
            } catch {
                completion(error)
            }
        }
        return TaskCancellable(task: task)
    }

    private final class TaskCancellable: Cancellable, @unchecked Sendable {
        let task: Task<Void, Never>
        init(task: Task<Void, Never>) { self.task = task }
        func cancel() { task.cancel() }
    }
}

/// Page links of E-Hentai galleries, filled from their thumbnail pages.
actor EhPageDirectory {
    static let shared = EhPageDirectory()

    private var links: [Int: [Int: String]] = [:]
    private var perPage: [Int: Int] = [:]

    // Page requests take turns here, a few at a time, the newest first: the
    // page being read goes before pages turned past, and a page that is no
    // longer wanted leaves the line (the site answers a few a second).
    private static let atOnce = 2
    private var running = 0
    private var waiting: [(id: UUID, resume: CheckedContinuation<Void, Error>)] = []

    private func takeTurn() async throws {
        if running < Self.atOnce {
            running += 1
            return
        }
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (resume: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { resume.resume(throwing: CancellationError()) } else { waiting.append((id, resume)) }
            }
        } onCancel: {
            Task { await self.leaveLine(id) }
        }
    }

    private func leaveLine(_ id: UUID) {
        guard let index = waiting.firstIndex(where: { $0.id == id }) else { return }
        waiting.remove(at: index).resume.resume(throwing: CancellationError())
    }

    /// The turn passes to the newest waiting request.
    private func endTurn() {
        if let next = waiting.popLast() { next.resume.resume() } else { running -= 1 }
    }

    func remember(gid: Int, thumbnails: [EhThumbnail]) {
        guard !thumbnails.isEmpty else { return }
        var map = links[gid] ?? [:]
        for t in thumbnails { map[Int(t.page)] = t.pageUrl }
        links[gid] = map
        if perPage[gid] == nil { perPage[gid] = thumbnails.count }
    }

    private func link(gid: Int, token: String, page: Int) async throws -> String {
        if let link = links[gid]?[page] { return link }
        let per = perPage[gid] ?? 20
        let thumbs = try await EhService.shared.thumbnails(gid: gid, token: token, page: (page - 1) / max(per, 1))
        remember(gid: gid, thumbnails: thumbs)
        guard let link = links[gid]?[page] else { throw EhError.failed(String(localized: "This page isn't available.")) }
        return link
    }

    func image(gid: Int, token: String, page: Int) async throws -> Data {
        try await takeTurn()
        defer { endTurn() }
        try Task.checkCancellation()
        let pageLink = try await link(gid: gid, token: token, page: page)
        var reloadKey: String?
        var lastError: Error = EhError.failed(String(localized: "Couldn't load the image."))
        for _ in 0..<3 {
            let info = try await EhService.shared.call { try await $0.imagePage(pageUrl: pageLink, reloadKey: reloadKey) }
            if let url = URL(string: info.imageUrl) {
                do {
                    var request = URLRequest(url: url)
                    request.timeoutInterval = 60
                    let (data, response) = try await URLSession.shared.data(for: request)
                    if (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty { return data }
                } catch {
                    lastError = error
                }
            }
            guard let key = info.reloadKey else { break }
            reloadKey = key
        }
        throw lastError
    }
}

/// Cuts one tile out of an E-Hentai thumbnail sprite sheet.
struct SpriteCrop: ImageProcessing {
    let x: Int
    let width: Int
    let height: Int

    var identifier: String { "sprite(\(x),\(width),\(height))" }

    func process(_ image: PlatformImage) -> PlatformImage? {
        guard let cg = image.cgImage else { return image }
        // Thumbnails are laid out in points of the original image.
        let scale = CGFloat(cg.width) / max(image.size.width, 1)
        let rect = CGRect(x: CGFloat(x) * scale, y: 0, width: CGFloat(width) * scale, height: CGFloat(height) * scale)
            .intersection(CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        guard !rect.isEmpty, let cropped = cg.cropping(to: rect) else { return image }
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    }
}

/// A preview tile of an E-Hentai gallery (a cut-out of a sprite sheet, or a whole image).
struct EhThumbnailView: View {
    let thumb: EhThumbnail

    var body: some View {
        let processors: [any ImageProcessing] = thumb.isSprite && thumb.width > 0
            ? [SpriteCrop(x: Int(thumb.offsetX), width: Int(thumb.width), height: Int(thumb.height))]
            : []
        LazyImageView(url: URL(string: thumb.imageUrl), processors: processors)
    }
}

/// A Nuke image with processors, fitted in its frame.
private struct LazyImageView: View {
    let url: URL?
    let processors: [any ImageProcessing]

    var body: some View {
        LazyImage(request: url.map { ImageRequest(url: $0, processors: processors) }) { state in
            if let image = state.image {
                image.resizable().scaledToFit()
            } else {
                Color(.quaternarySystemFill)
            }
        }
    }
}
