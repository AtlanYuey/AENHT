import Foundation
import Observation
import UIKit
import Shared

/// A downloaded (or downloading) gallery, stored under
/// Application Support/Downloads/<id>/.
struct DownloadRecord: Codable, Identifiable, Hashable {
    enum State: String, Codable {
        case queued, downloading, paused, finished, failed
    }

    struct Page: Codable, Hashable {
        let number: Int
        let remote: String
        let file: String
        let width: Int
        let height: Int
    }

    let id: Int
    let mediaID: String
    var title: String
    var thumbnail: String?
    var thumbWidth: Int
    var thumbHeight: Int
    var pages: [Page]
    var completed: Int
    var state: State
    var added: Date
    /// For the language badge and tags on cards (nil for downloads from before 2.1.0).
    var tagIDs: [Int]? = nil
    /// nil: nhentai.
    var site: SiteKind? = nil
    /// E-Hentai gallery token.
    var token: String? = nil
    /// E-Hentai: all pages (their links are filled in while downloading).
    var pageCount: Int? = nil
    var categoryLabel: String? = nil
    var language: String? = nil

    var siteKind: SiteKind { site ?? .nhentai }
    var uid: String { siteKind == .nhentai ? "nh:\(id)" : "eh:\(id):\(token ?? "")" }
    var totalPages: Int { max(pages.count, pageCount ?? 0) }

    var progress: Double {
        totalPages == 0 ? 0 : Double(completed) / Double(totalPages)
    }

    var item: GalleryItem {
        var item = GalleryItem(id: id, mediaID: mediaID, title: title, thumbnail: siteKind == .nhentai ? thumbnail : nil,
                               thumbnailWidth: thumbWidth, thumbnailHeight: thumbHeight,
                               numPages: totalPages, tagIDs: tagIDs ?? [])
        if siteKind == .ehentai {
            item.site = .ehentai
            item.token = token
            item.absoluteThumb = thumbnail
            item.ehLanguage = language
        }
        return item
    }
}

/// Downloads gallery pages for offline reading (three at a time).
@MainActor
@Observable
final class DownloadManager {
    static let shared = DownloadManager()

    private(set) var records: [DownloadRecord] = []
    @ObservationIgnored private var tasks: [String: Task<Void, Never>] = [:]
    /// Which run of a download is the current one: pausing, resuming and
    /// deleting start a new number, and a run that isn't current any more
    /// (it was cancelled and is still winding down) changes nothing.
    @ObservationIgnored private var runs: [String: Int] = [:]
    @ObservationIgnored private var lastSave = Date.distantPast
    @ObservationIgnored private var indexRevision = 0
    @ObservationIgnored private let indexWriter = IndexWriter()

    private init() {
        records = Self.loadIndex()
        // Downloads interrupted by the app being closed resume as paused.
        for index in records.indices where records[index].state == .downloading || records[index].state == .queued {
            records[index].state = .paused
        }
    }

    // MARK: Queries

    func record(_ id: Int) -> DownloadRecord? {
        records.first { $0.siteKind == .nhentai && $0.id == id }
    }

    func record(uid: String) -> DownloadRecord? {
        records.first { $0.uid == uid }
    }

    /// Local file of a page if it has been downloaded.
    func localURL(gallery id: Int, page index: Int) -> URL? {
        guard let record = record(id) else { return nil }
        return localURL(record, index)
    }

    func localURL(item: GalleryItem, page index: Int) -> URL? {
        guard let record = record(uid: item.uid) else { return nil }
        return localURL(record, index)
    }

    private func localURL(_ record: DownloadRecord, _ index: Int) -> URL? {
        guard record.pages.indices.contains(index) else { return nil }
        let url = Self.folder(record).appendingPathComponent(record.pages[index].file)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// The page of a downloaded gallery without the network: the gallery's
    /// JSON saved with the download, or what the download record knows.
    func offlineDetail(_ id: Int) -> GalleryDetail? {
        guard let record = record(id) else { return nil }
        if let data = try? Data(contentsOf: Self.detailURL(id)),
           let detail = try? NHClient.decodeGallery(data) {
            return detail
        }
        return GalleryDetail(record: record)
    }

    /// Keeps the gallery's JSON ([data], as the API sent it) with its
    /// download, for its page offline.
    func saveDetail(_ data: Data, of detail: GalleryDetail) {
        guard record(detail.id) != nil else { return }
        if record(detail.id)?.tagIDs == nil {
            update(detail.id) { $0.tagIDs = detail.tags.map(\.id) }
            saveIndex()
        }
        let url = Self.detailURL(detail.id)
        Task.detached(priority: .utility) {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: Actions

    /// Downloads [detail]; [data] is its JSON, kept for its page offline.
    func start(_ detail: GalleryDetail, data: Data? = nil) {
        if record(detail.id) == nil {
            let item = detail.asItem
            let pages = detail.pages.enumerated().map { offset, page in
                let ext = (page.path as NSString).pathExtension
                return DownloadRecord.Page(
                    number: page.number,
                    remote: page.imageURL?.absoluteString ?? "",
                    file: String(format: "%04d.%@", offset + 1, ext.isEmpty ? "jpg" : ext),
                    width: page.width,
                    height: page.height
                )
            }
            records.insert(DownloadRecord(
                id: detail.id, mediaID: detail.mediaID, title: detail.displayTitle,
                thumbnail: item.thumbnail, thumbWidth: item.thumbnailWidth,
                thumbHeight: item.thumbnailHeight, pages: pages, completed: 0,
                state: .queued, added: Date(), tagIDs: detail.tags.map(\.id)
            ), at: 0)
            saveIndex()
        }
        if let data { saveDetail(data, of: detail) }
        resume(detail.id)
    }

    /// Downloads an E-Hentai gallery ([card] gives its cover).
    func start(eh detail: EhGalleryDetail, card: GalleryItem) {
        let uid = "eh:\(detail.gid):\(detail.token)"
        if record(uid: uid) == nil {
            records.insert(DownloadRecord(
                id: Int(detail.gid), mediaID: "", title: detail.title,
                thumbnail: card.absoluteThumb ?? detail.cover, thumbWidth: card.thumbnailWidth,
                thumbHeight: card.thumbnailHeight, pages: Self.ehPages(detail.thumbnails), completed: 0,
                state: .queued, added: Date(), site: .ehentai, token: detail.token,
                pageCount: Int(detail.pages), categoryLabel: detail.category?.label,
                language: detail.language?.split(separator: " ").first.map { $0.lowercased() }
            ), at: 0)
            saveIndex()
        }
        resume(uid: uid)
    }

    nonisolated private static func ehPages(_ thumbs: [EhThumbnail]) -> [DownloadRecord.Page] {
        thumbs.map { DownloadRecord.Page(number: Int($0.page), remote: $0.pageUrl,
                                         file: String(format: "%04d.img", Int($0.page)), width: 0, height: 0) }
    }

    func resume(_ id: Int) { resume(uid: "nh:\(id)") }
    func pause(_ id: Int) { pause(uid: "nh:\(id)") }
    func delete(_ id: Int) { delete(uid: "nh:\(id)") }

    func resume(uid: String) {
        guard tasks[uid] == nil, let index = records.firstIndex(where: { $0.uid == uid }),
              records[index].state != .finished else { return }
        records[index].state = .downloading
        saveIndex()
        let run = nextRun(uid)
        tasks[uid] = Task { [weak self] in
            await self?.run(uid, run)
        }
    }

    private func nextRun(_ uid: String) -> Int {
        let run = (runs[uid] ?? 0) + 1
        runs[uid] = run
        return run
    }

    private func isCurrent(_ uid: String, _ run: Int) -> Bool { runs[uid] == run && !Task.isCancelled }

    func pause(uid: String) {
        tasks[uid]?.cancel()
        tasks[uid] = nil
        _ = nextRun(uid)
        update(uid) { $0.state = .paused }
        saveIndex()
    }

    func delete(uid: String) {
        tasks[uid]?.cancel()
        tasks[uid] = nil
        _ = nextRun(uid)
        if let record = record(uid: uid) {
            try? FileManager.default.removeItem(at: Self.folder(record))
        }
        records.removeAll { $0.uid == uid }
        saveIndex()
    }

    // MARK: Worker

    /// Galleries downloading now (Download Settings limits how many at once).
    private var running = 0

    private func run(_ uid: String, _ run: Int) async {
        // Waits its turn: the others keep downloading meanwhile.
        if running >= AppSettings.shared.downloadGalleries {
            update(uid) { $0.state = .queued }
            while running >= AppSettings.shared.downloadGalleries {
                if !isCurrent(uid, run) { return }
                try? await Task.sleep(for: .milliseconds(500))
            }
            guard isCurrent(uid, run) else { return }
            update(uid) { $0.state = .downloading }
        }
        running += 1
        defer { running -= 1 }
        await download(uid, run)
    }

    private func download(_ uid: String, _ run: Int) async {
        if record(uid: uid)?.siteKind == .ehentai, !(await completeEhPages(uid, run)) {
            // Paused or deleted meanwhile: that isn't a failure, and the state isn't this run's to set.
            guard isCurrent(uid, run) else { return }
            tasks[uid] = nil
            update(uid) { $0.state = .failed }
            saveIndex()
            return
        }
        guard isCurrent(uid, run), let record = record(uid: uid) else { return }
        let folder = Self.folder(record)
        let isEh = record.siteKind == .ehentai
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        // Pages an earlier run didn't finish.
        for name in (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [] where name.contains(".part") {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
        var failures = 0
        // A file that isn't a picture (an error page saved by an earlier version) is downloaded again.
        let pending = record.pages.filter { Self.imageExtension(of: folder.appendingPathComponent($0.file)) == nil }
        update(uid) { $0.completed = $0.pages.count - pending.count }

        let settings = AppSettings.shared
        let attempts = settings.downloadRetries + 1
        let interval = settings.downloadInterval
        let original = settings.ehOriginalImages
        let atOnce = isEh ? min(3, settings.downloadPages) : settings.downloadPages
        // Each page goes to a file of this run first; only while the run is still
        // the current one is it moved into place (a paused, deleted or restarted
        // download never writes over the pages of the one that replaced it).
        await withTaskGroup(of: (file: URL, part: URL?).self) { group in
            var iterator = pending.makeIterator()
            func addNext() -> Bool {
                guard let page = iterator.next() else { return false }
                group.addTask {
                    if interval > 0 { try? await Task.sleep(for: .seconds(interval)) }
                    let file = folder.appendingPathComponent(page.file)
                    let part = folder.appendingPathComponent("\(page.file).part\(run)")
                    let ok = isEh ? await Self.fetchEh(page, to: part, attempts: attempts, original: original)
                        : await Self.fetch(page.remote, to: part, attempts: attempts)
                    return (file, ok ? part : nil)
                }
                return true
            }
            // E-Hentai: at most 3 at a time (each page also loads its page link).
            for _ in 0..<atOnce {
                if !addNext() { break }
            }
            for await result in group {
                guard isCurrent(uid, run) else {
                    if let part = result.part { try? FileManager.default.removeItem(at: part) }
                    group.cancelAll()
                    continue // Waits for the others, to remove their files too.
                }
                if let part = result.part, Self.commit(part, to: result.file) {
                    update(uid) { $0.completed += 1 }
                } else {
                    failures += 1
                }
                if Date().timeIntervalSince(lastSave) > 2 { saveIndex() }
                _ = addNext()
            }
        }

        // A run that was paused or replaced leaves the state to whoever did that.
        guard isCurrent(uid, run) else { return }
        tasks[uid] = nil
        update(uid) { record in
            record.state = failures == 0 && record.completed >= record.totalPages ? .finished : .failed
        }
        saveIndex()
    }

    /// Moves a downloaded page into place.
    nonisolated private static func commit(_ part: URL, to file: URL) -> Bool {
        do {
            if FileManager.default.fileExists(atPath: file.path) {
                _ = try FileManager.default.replaceItemAt(file, withItemAt: part)
            } else {
                try FileManager.default.moveItem(at: part, to: file)
            }
            return true
        } catch {
            try? FileManager.default.removeItem(at: part)
            return false
        }
    }

    /// Fills in the page links of an E-Hentai download from its thumbnail pages.
    private func completeEhPages(_ uid: String, _ run: Int) async -> Bool {
        guard var record = record(uid: uid), let token = record.token else { return false }
        var thumbPage = 1
        while record.pages.count < record.totalPages {
            guard isCurrent(uid, run) else { return false }
            guard let thumbs = try? await EhService.shared.thumbnails(gid: record.id, token: token, page: thumbPage),
                  !thumbs.isEmpty else { break }
            // Paused, deleted or restarted while the page loaded: its pages aren't this run's to add.
            guard isCurrent(uid, run) else { return false }
            update(uid) { current in
                let known = Set(current.pages.map(\.number))
                let added = Self.ehPages(thumbs.filter { !known.contains(Int($0.page)) })
                current.pages = (current.pages + added).sorted { $0.number < $1.number }
            }
            guard let updated = self.record(uid: uid) else { return false }
            record = updated
            thumbPage += 1
        }
        guard isCurrent(uid, run) else { return false }
        saveIndex()
        return record.pages.count >= record.totalPages
    }

    /// An E-Hentai page: its image link first, then the image (another node when it fails).
    nonisolated private static func fetchEh(_ page: DownloadRecord.Page, to file: URL, attempts: Int, original: Bool) async -> Bool {
        var reloadKey: String?
        for attempt in 0..<max(1, attempts) {
            if Task.isCancelled { return false }
            guard let info = try? await EhService.shared.call({ try await $0.imagePage(pageUrl: page.remote, reloadKey: reloadKey) }) else {
                try? await Task.sleep(for: .seconds(Double(2 * (attempt + 1))))
                continue
            }
            let url = original ? (info.originalUrl ?? info.imageUrl) : info.imageUrl
            if await fetch(url, to: file, attempts: 1) { return true }
            reloadKey = info.reloadKey
        }
        return false
    }

    nonisolated private static func fetch(_ remote: String, to file: URL, attempts: Int = 3) async -> Bool {
        guard let url = URL(string: remote) else { return false }
        var request = URLRequest(url: url)
        // Original images (and ExHentai's servers) need the account's cookies.
        if let cookies = EhService.shared.api.imageCookieHeader(url: remote) {
            request.setValue(cookies, forHTTPHeaderField: "Cookie")
        }
        for attempt in 0..<attempts {
            if Task.isCancelled { return false }
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                // An answer that isn't a picture (an error page with status 200) is not the page.
                guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                      imageExtension(of: data) != nil else {
                    throw URLError(.badServerResponse)
                }
                try data.write(to: file, options: .atomic)
                return true
            } catch {
                if attempt < attempts - 1 { try? await Task.sleep(for: .seconds(Double(attempt + 1))) }
            }
        }
        return false
    }

    // MARK: CBZ

    /// The finished download as a CBZ (the pages in order and a
    /// ComicInfo.xml, zipped by the system), for sharing or saving to Files.
    nonisolated static func exportCBZ(_ record: DownloadRecord) throws -> URL {
        let fm = FileManager.default
        let name = String(record.title.map { "/\\:*?\"<>|".contains($0) ? "_" : $0 }.prefix(120))
        let base = fm.temporaryDirectory.appendingPathComponent("cbz-\(record.id)", isDirectory: true)
        try? fm.removeItem(at: base)
        let content = base.appendingPathComponent(name.isEmpty ? "\(record.id)" : name, isDirectory: true)
        try fm.createDirectory(at: content, withIntermediateDirectories: true)
        let folder = Self.folder(record)
        var exported = 0
        for (i, page) in record.pages.sorted(by: { $0.number < $1.number }).enumerated() {
            let source = folder.appendingPathComponent(page.file)
            // Named by what the file is (E-Hentai pages are stored without their type).
            guard let ext = imageExtension(of: source) else { continue }
            try fm.copyItem(at: source, to: content.appendingPathComponent(String(format: "%04d.%@", i + 1, ext)))
            exported += 1
        }
        func esc(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
        let web = record.siteKind == .ehentai ? "https://e-hentai.org/g/\(record.id)/\(record.token ?? "")/" : "https://nhentai.net/g/\(record.id)/"
        let info = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo>\n  <Title>\(esc(record.title))</Title>\n  <PageCount>\(exported)</PageCount>\n  <Web>\(esc(web))</Web>\n</ComicInfo>\n"
        try Data(info.utf8).write(to: content.appendingPathComponent("ComicInfo.xml"))
        // Reading a folder "for uploading" gives it zipped.
        var zipped: URL?
        var coordinatorError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: content, options: .forUploading, error: &coordinatorError) { zip in
            let target = base.appendingPathComponent((name.isEmpty ? "\(record.id)" : name) + ".cbz")
            do {
                try fm.copyItem(at: zip, to: target)
                zipped = target
            } catch {
                copyError = error
            }
        }
        if let coordinatorError { throw coordinatorError }
        if let copyError { throw copyError }
        guard let zipped else { throw CocoaError(.fileWriteUnknown) }
        return zipped
    }

    private func update(_ id: Int, _ change: (inout DownloadRecord) -> Void) {
        update("nh:\(id)", change)
    }

    private func update(_ uid: String, _ change: (inout DownloadRecord) -> Void) {
        guard let index = records.firstIndex(where: { $0.uid == uid }) else { return }
        change(&records[index])
    }

    // MARK: Storage

    nonisolated static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Downloads", isDirectory: true)
    }

    nonisolated static func folder(_ id: Int) -> URL {
        root.appendingPathComponent("\(id)", isDirectory: true)
    }

    nonisolated static func folder(_ record: DownloadRecord) -> URL {
        record.siteKind == .ehentai ? root.appendingPathComponent("eh-\(record.id)", isDirectory: true) : folder(record.id)
    }

    nonisolated private static func detailURL(_ id: Int) -> URL {
        folder(id).appendingPathComponent("gallery.json")
    }

    nonisolated private static var indexURL: URL {
        root.appendingPathComponent("index.json")
    }

    nonisolated private static var indexBackupURL: URL {
        root.appendingPathComponent("index.json.bak")
    }

    /// The list of downloads, or the copy before its last save when it can't be read.
    nonisolated private static func loadIndex() -> [DownloadRecord] {
        for url in [indexURL, indexBackupURL] {
            if let data = try? Data(contentsOf: url), let list = try? JSONDecoder().decode([DownloadRecord].self, from: data) {
                return list
            }
        }
        return []
    }

    private func saveIndex() {
        lastSave = Date()
        let records = records
        indexRevision += 1
        let revision = indexRevision
        let writer = indexWriter
        Task.detached(priority: .utility) {
            guard let data = try? JSONEncoder().encode(records) else { return }
            await writer.write(data, revision: revision, to: Self.indexURL, backup: Self.indexBackupURL, in: Self.root)
        }
    }

    /// Saves of the list, one at a time: an older list never lands after a
    /// newer one, and the list before the save stays as a backup.
    private actor IndexWriter {
        private var written = 0

        func write(_ data: Data, revision: Int, to url: URL, backup: URL, in folder: URL) {
            guard revision > written else { return }
            written = revision
            let fm = FileManager.default
            try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
            if fm.fileExists(atPath: url.path) {
                try? fm.removeItem(at: backup)
                try? fm.copyItem(at: url, to: backup)
            }
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                Diagnostics.shared.log(category: "downloads", message: "Saving the list: \(error.localizedDescription)")
            }
        }
    }

    // MARK: Pictures

    /// The type of a picture by its first bytes ("jpg", "png", "gif",
    /// "webp", "avif", "heic", "bmp"), or nil when it isn't a picture or was
    /// cut off (a download that stopped part way: the type's end marker or
    /// its own stated length is missing).
    nonisolated static func imageExtension(of data: Data) -> String? {
        let size = data.count
        return imageType(size: Int64(size)) { offset, count in
            let start = data.startIndex + Int(offset)
            guard offset >= 0, Int(offset) + count <= size else { return nil }
            return data.subdata(in: start..<start + count)
        }
    }

    /// The type of the picture in a file, or nil when it is missing, isn't one, or was cut off.
    nonisolated static func imageExtension(of file: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: file),
              let size = try? handle.seekToEnd() else { return nil }
        defer { try? handle.close() }
        return imageType(size: Int64(size)) { offset, count in
            guard (try? handle.seek(toOffset: UInt64(offset))) != nil,
                  let bytes = try? handle.read(upToCount: count), bytes.count == count else { return nil }
            return bytes
        }
    }

    /// The checks of `imageExtension`, on bytes read with `read(offset, count)`.
    /// The same as Android's (`ImageEnds` in Stores.kt).
    nonisolated private static func imageType(size: Int64, read: (Int64, Int) -> Data?) -> String? {
        guard size >= 12, let headData = read(0, 12) else { return nil }
        let head = [UInt8](headData)
        func at(_ offset: Int, _ text: String) -> Bool { Array(text.utf8) == Array(head[offset..<offset + text.utf8.count]) }
        let type: String
        if head[0] == 0xFF, head[1] == 0xD8, head[2] == 0xFF { type = "jpg" }
        else if head[0] == 0x89, at(1, "PNG") { type = "png" }
        else if at(0, "GIF8") { type = "gif" }
        else if at(0, "RIFF"), at(8, "WEBP") { type = "webp" }
        else if at(4, "ftyp"), at(8, "avif") || at(8, "avis") { type = "avif" }
        else if at(4, "ftyp"), at(8, "heic") || at(8, "heix") || at(8, "mif1") { type = "heic" }
        else if at(0, "BM") { type = "bmp" }
        else { return nil }

        func tail(_ count: Int) -> [UInt8] {
            let count = Int(min(Int64(count), size))
            return read(size - Int64(count), count).map { [UInt8]($0) } ?? []
        }
        func contains(_ bytes: [UInt8], _ marker: [UInt8]) -> Bool {
            guard bytes.count >= marker.count else { return false }
            return (0...(bytes.count - marker.count)).contains { i in marker.indices.allSatisfy { bytes[i + $0] == marker[$0] } }
        }
        func number(_ bytes: [UInt8], _ offset: Int, _ count: Int, littleEndian: Bool) -> Int64 {
            let part = bytes[offset..<offset + count]
            return (littleEndian ? Array(part.reversed()) : Array(part)).reduce(Int64(0)) { $0 << 8 | Int64($1) }
        }
        let complete: Bool
        switch type {
        // The end of image marker (some files carry a few bytes after it).
        case "jpg": complete = contains(tail(512), [0xFF, 0xD9])
        case "png": complete = contains(tail(64), Array("IEND".utf8) + [0xAE, 0x42, 0x60, 0x82]) // With its CRC.
        case "gif": complete = tail(1) == [0x3B] // The trailer.
        // The length the file says it has.
        case "webp": complete = number(head, 4, 4, littleEndian: true) + 8 <= size
        case "bmp": complete = (26...max(26, size)).contains(number(head, 2, 4, littleEndian: true))
        // Boxes one after the other, each within the file, the last ending at its end.
        default:
            var offset: Int64 = 0
            var boxes = 0
            var ends = true
            walk: while offset < size {
                boxes += 1
                guard size - offset >= 8, boxes <= 4096, let header = read(offset, 8) else { ends = false; break }
                var length = number([UInt8](header), 0, 4, littleEndian: false)
                switch length {
                case 0: offset = size; break walk // To the end of the file.
                case 1:
                    guard size - offset >= 16, let large = read(offset + 8, 8) else { ends = false; break walk }
                    length = number([UInt8](large), 0, 8, littleEndian: false)
                    guard length >= 16 else { ends = false; break walk }
                default:
                    guard length >= 8 else { ends = false; break walk }
                }
                offset += length
            }
            complete = ends && offset == size
        }
        return complete ? type : nil
    }

    /// Bytes used by downloads.
    nonisolated static func diskUsage() -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }
}
