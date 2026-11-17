import Foundation
import Shared

enum NHError: LocalizedError {
    case http(Int, String)
    case notLoggedIn
    case invalidResponse
    case needsVerification
    case network(String)
    /// The login changed while the request waited (another account, or logged out).
    case loginChanged
    /// A new login couldn't be saved on the device; the one there was stays.
    case notSaved

    var errorDescription: String? {
        switch self {
        case let .http(code, message):
            message.isEmpty ? String(localized: "Request failed (\(code))") : message
        case .notLoggedIn:
            String(localized: "Please log in first")
        case .invalidResponse:
            String(localized: "Unexpected response from the server")
        case .needsVerification:
            String(localized: "Please log in first")
        case let .network(message):
            ErrorText.localized(message)
        case .loginChanged:
            String(localized: "Please log in first")
        case .notSaved:
            String(localized: "The login couldn't be saved on this device.")
        }
    }
}

enum SearchSort: String, CaseIterable, Identifiable, Codable {
    case recent = "date"
    case popularToday = "popular-today"
    case popularWeek = "popular-week"
    case popularMonth = "popular-month"
    case popular

    var id: String { rawValue }

    var title: String {
        switch self {
        case .recent: String(localized: "Recent")
        case .popularToday: String(localized: "Popular Today")
        case .popularWeek: String(localized: "Popular This Week")
        case .popularMonth: String(localized: "Popular This Month")
        case .popular: String(localized: "All-Time Popular")
        }
    }
}

enum LanguageFilter: String, CaseIterable, Identifiable, Codable {
    case all = ""
    case japanese
    case chinese
    case english
    case translated

    var id: String { rawValue.isEmpty ? "all" : rawValue }

    var title: String {
        switch self {
        case .all: String(localized: "All Languages")
        case .japanese: String(localized: "Japanese")
        case .chinese: String(localized: "Chinese")
        case .english: String(localized: "English")
        case .translated: String(localized: "Translated")
        }
    }

    /// Search syntax added to a query.
    var queryTerm: String? { self == .all ? nil : "language:\(rawValue)" }
}

/// nhentai REST API v2, through the shared Kotlin module: it waits for the
/// official rate limits, renews the login and solves the proof of work for
/// comments. The JSON it returns is decoded with the app's own models.
final class NHClient: @unchecked Sendable {
    static let shared = NHClient()

    let api: NhApi
    private let decoder = JSONDecoder()

    private init() {
        AppInfo.shared.version = AppVersion.current
        api = NhApi(tokens: AuthStore.shared)
    }

    /// Runs a shared call and turns its Kotlin exception into an `NHError`.
    private func call(_ block: () async throws -> String) async throws -> Data {
        do {
            return Data(try await block().utf8)
        } catch {
            throw Self.map(error)
        }
    }

    private static func map(_ error: Error) -> Error {
        guard let exception = (error as NSError).userInfo["KotlinException"] as? NhException else {
            return error
        }
        switch exception.kind {
        case "http": return NHError.http(Int(exception.code), exception.serverMessage ?? "")
        case "notLoggedIn": return NHError.notLoggedIn
        case "needsVerification": return NHError.needsVerification
        case "network": return NHError.network(exception.message ?? "")
        case "loginChanged": return NHError.loginChanged
        case "notSaved": return NHError.notSaved
        default: return NHError.invalidResponse
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw NHError.invalidResponse
        }
    }

    // MARK: Galleries

    /// Latest galleries, or a search when a query or a non-date sort is
    /// given ("*" matches everything).
    func galleries(query: String, sort: SearchSort, page: Int) async throws -> PageResponse<GalleryItem> {
        let data = try await call { try await api.galleriesJson(query: query, sort: sort.rawValue, page: Int32(page)) }
        return try decode(PageResponse<GalleryItem>.self, data)
    }

    func popular() async throws -> [GalleryItem] {
        let data = try await call { try await api.popularJson() }
        return try decode([GalleryItem].self, data).filter { !$0.blacklisted }
    }

    func gallery(_ id: Int) async throws -> GalleryDetail {
        try Self.decodeGallery(await galleryData(id))
    }

    /// A gallery's JSON as the API sends it (downloads keep it for offline use).
    func galleryData(_ id: Int) async throws -> Data {
        try await call { try await api.galleryJsonText(id: Int32(id)) }
    }

    static func decodeGallery(_ data: Data) throws -> GalleryDetail {
        do {
            return try JSONDecoder().decode(GalleryDetail.self, from: data)
        } catch {
            throw NHError.invalidResponse
        }
    }

    /// The newest comments (the first page).
    func comments(_ id: Int) async throws -> [GalleryComment] {
        try await commentsPage(id, page: 1).result
    }

    /// A page of comments (50 each), with how many pages there are.
    func commentsPage(_ id: Int, page: Int) async throws -> PageResponse<GalleryComment> {
        let data = try await call { try await api.commentsJson(id: Int32(id), page: Int32(page)) }
        return try decode(PageResponse<GalleryComment>.self, data)
    }

    // MARK: Account

    /// A random gallery's number.
    func randomID() async throws -> Int {
        do {
            return Int(try await api.randomGalleryId())
        } catch {
            throw Self.map(error)
        }
    }

    func favorites(page: Int, query: String = "") async throws -> PageResponse<GalleryItem> {
        let data = try await call { try await api.favoritesJson(page: Int32(page), query: query) }
        return try decode(PageResponse<GalleryItem>.self, data)
    }

    func setFavorite(_ id: Int, _ favorite: Bool) async throws -> FavoriteResult {
        let data = try await call { try await api.setFavoriteJson(id: Int32(id), favorite: favorite) }
        return try decode(FavoriteResult.self, data)
    }

    func loginWithApiKey(_ key: String) async throws {
        do {
            _ = try await api.loginWithApiKey(key: key)
        } catch {
            throw Self.map(error)
        }
    }

    func currentUser() async throws -> CurrentUser {
        let data = try await call { try await api.currentUserJson() }
        return try decode(CurrentUser.self, data)
    }

    func postComment(_ id: Int, body: String) async throws -> GalleryComment {
        let data = try await call { try await api.postCommentJson(id: Int32(id), body: body) }
        return try decode(GalleryComment.self, data)
    }

    // MARK: Tags

    func suggestTags(_ text: String) async throws -> [TagSuggestion] {
        let data = try await call { try await api.suggestTagsJson(text: text) }
        return try decode([TagSuggestion].self, data)
    }
}
