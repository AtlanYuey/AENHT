import Foundation
import Observation
import Shared
import SwiftData
import UIKit

/// The app's SwiftData container (reading history), shared by the views and WebDAV sync.
@MainActor
enum AppModel {
    static let container: ModelContainer = {
        do { return try ModelContainer(for: HistoryEntry.self) } catch { fatalError("History store: \(error)") }
    }()
}

/// Sync through the user's own WebDAV server: settings, reading history and
/// the favorites copy (the shared WebDavSync does the merging). The history
/// lives in SwiftData here, so it is copied into the shared database before
/// a sync and the merged result is written back. The address and user are
/// kept on this device, the password in the Keychain; none of them is
/// exported or synced.
@MainActor
@Observable
final class WebDavService {
    static let shared = WebDavService()

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private lazy var engine = WebDavSync(db: IosDatabase.shared.shared)
    @ObservationIgnored lazy var history = HistoryRepository(db: IosDatabase.shared.shared)

    private(set) var url: String
    private(set) var user: String
    var auto: Bool { didSet { defaults.set(auto, forKey: "webdavAuto") } }
    private(set) var lastSync: Date?
    private(set) var syncing = false
    private(set) var error: String?
    private var revision = 0

    private init() {
        url = defaults.string(forKey: "webdavURL") ?? ""
        user = defaults.string(forKey: "webdavUser") ?? ""
        auto = defaults.object(forKey: "webdavAuto") as? Bool ?? true
        lastSync = defaults.object(forKey: "webdavLastSync") as? Date
    }

    private var password: String? { Keychain.read("webdav_password") }

    var isConfigured: Bool {
        _ = revision
        return !url.isEmpty && !user.isEmpty && !(password ?? "").isEmpty
    }

    func save(url: String, user: String, password: String) {
        self.url = url.trimmingCharacters(in: .whitespaces)
        self.user = user.trimmingCharacters(in: .whitespaces)
        defaults.set(self.url, forKey: "webdavURL")
        defaults.set(self.user, forKey: "webdavUser")
        if !password.isEmpty { Keychain.write("webdav_password", password) }
        revision += 1
    }

    /// Forgets the server (the files on it stay).
    func disconnect() {
        url = ""; user = ""; lastSync = nil; error = nil
        for key in ["webdavURL", "webdavUser", "webdavLastSync"] { defaults.removeObject(forKey: key) }
        Keychain.delete("webdav_password")
        revision += 1
    }

    private func message(_ error: Error) -> String {
        let kotlin = (error as NSError).userInfo["KotlinException"]
        if let e = kotlin as? WebDavException {
            if e.unauthorized { return String(localized: "The server refused the user name or password.") }
            if e.newerVersion { return String(localized: "The file on the server was written by a newer version of AENHT. Update the app to sync.") }
            if e.conflict { return String(localized: "Another device is syncing at the same time. Nothing was overwritten; try again in a moment.") }
            return ErrorText.of(error)
        }
        return ErrorText.of(error)
    }

    /// Checks the address and sign-in; nil when they work, else what went wrong.
    func test(url: String, user: String, password: String) async -> String? {
        do {
            try await engine.test(baseUrl: url.trimmingCharacters(in: .whitespaces), user: user.trimmingCharacters(in: .whitespaces),
                                  password: password.isEmpty ? (self.password ?? "") : password)
            return nil
        } catch {
            return message(error)
        }
    }

    func syncNow() {
        guard isConfigured, !syncing, let password else { return }
        syncing = true
        error = nil
        Task {
            defer { syncing = false }
            do {
                // What is read while the sync waits for the server isn't in its answer:
                // those entries are newer than this moment and are kept.
                let started = Date()
                try await pushHistory()
                let settings = AppSettings.shared
                let json = (try? JSONSerialization.data(withJSONObject: settings.syncedSnapshot(), options: [.sortedKeys]))
                    .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                let result = try await engine.sync(baseUrl: url, user: user, password: password, device: UIDevice.current.name,
                                                   settings: json, settingsChangedAt: settings.changedAt)
                if let remote = result.settings, let data = remote.data(using: .utf8),
                   let values = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    settings.apply(values, fromSync: true)
                    settings.setChangedAt(result.settingsChangedAt)
                }
                if result.historyChanged > 0 { try await pullHistory(since: started) }
                if result.favoritesAdded > 0 { await FavoritesStore.shared.imported() }
                lastSync = .now
                defaults.set(lastSync, forKey: "webdavLastSync")
            } catch {
                self.error = message(error)
            }
        }
    }

    /// Automatic sync, when the app opens or goes to the background, at most every few [minutes].
    func syncIfDue(minutes: Double = 5) {
        guard auto, isConfigured else { return }
        if let lastSync, Date.now.timeIntervalSince(lastSync) < minutes * 60 { return }
        syncNow()
    }

    // MARK: History (SwiftData ⇄ shared database)

    private func pushHistory() async throws {
        let entries = try AppModel.container.mainContext.fetch(FetchDescriptor<HistoryEntry>())
        let records = entries.map { e in
            HistoryRecord(
                key: e.key, site: e.site, galleryId: Int64(e.gid), token: e.token, mediaId: e.mediaID, title: e.title,
                thumbnail: e.thumbnail, thumbWidth: Int32(e.thumbWidth), thumbHeight: Int32(e.thumbHeight),
                numPages: Int32(e.numPages), lastPage: Int32(e.lastPage),
                readAt: Int64(e.lastRead.timeIntervalSince1970 * 1000),
                tagIds: e.tagIDs.map(String.init).joined(separator: ","),
                rating: e.rating, language: e.ehLanguage, category: e.ehCategory
            )
        }
        try await history.put(records: records)
    }

    /// Brings the merged history back. An entry that isn't in it was deleted
    /// on another device, unless it was read after the sync began (`start`).
    private func pullHistory(since start: Date) async throws {
        let records = try await history.all()
        let context = AppModel.container.mainContext
        let entries = try context.fetch(FetchDescriptor<HistoryEntry>())
        let kept = Set(records.map(\.key))
        var byKey: [String: HistoryEntry] = [:]
        for e in entries {
            if kept.contains(e.key) { byKey[e.key] = e } else if e.lastRead < start { context.delete(e) }
        }
        for r in records {
            let date = Date(timeIntervalSince1970: Double(r.readAt) / 1000)
            let tags = r.tagIds.split(separator: ",").compactMap { Int($0) }
            if let e = byKey[r.key] {
                guard date > e.lastRead else { continue }
                e.lastRead = date
                e.lastPage = Int(r.lastPage)
                if !r.title.isEmpty { e.title = r.title }
                if r.numPages > 0 { e.numPages = Int(r.numPages) }
                if !tags.isEmpty { e.tagIDs = tags }
                if r.rating > 0 { e.rating = r.rating }
                if !r.language.isEmpty { e.ehLanguage = r.language }
                if !r.category.isEmpty { e.ehCategory = r.category }
            } else {
                context.insert(HistoryEntry(
                    key: r.key, gid: Int(r.galleryId), site: r.site, token: r.token, mediaID: r.mediaId, title: r.title,
                    thumbnail: r.thumbnail, thumbWidth: Int(r.thumbWidth), thumbHeight: Int(r.thumbHeight),
                    numPages: Int(r.numPages), tagIDs: tags, lastPage: Int(r.lastPage), lastRead: date
                ))
                let addedKey = r.key
                if let added = try? context.fetch(FetchDescriptor<HistoryEntry>(predicate: #Predicate { $0.key == addedKey })).first {
                    added.rating = r.rating
                    added.ehLanguage = r.language
                    added.ehCategory = r.category
                }
                byKey[r.key] = nil
            }
        }
        try context.save()
    }
}
