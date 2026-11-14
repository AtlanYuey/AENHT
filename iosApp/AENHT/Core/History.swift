import Foundation
import SwiftData

/// A gallery the user opened, with the last page read.
@Model
final class HistoryEntry {
    /// "nh:<id>" or "eh:<gid>:<token>".
    @Attribute(.unique) var key: String
    var gid: Int
    /// "nh" or "eh".
    var site: String = "nh"
    var token: String?
    var mediaID: String
    var title: String
    var thumbnail: String?
    var thumbWidth: Int
    var thumbHeight: Int
    var numPages: Int
    var lastPage: Int
    var lastRead: Date
    /// For the language badge and tags on cards (empty for entries from before 2.1.0).
    var tagIDs: [Int] = []

    init(key: String, gid: Int, site: String, token: String?, mediaID: String, title: String, thumbnail: String?,
         thumbWidth: Int, thumbHeight: Int, numPages: Int, tagIDs: [Int] = [],
         lastPage: Int = 0, lastRead: Date = .now) {
        self.key = key
        self.gid = gid
        self.site = site
        self.token = token
        self.mediaID = mediaID
        self.title = title
        self.thumbnail = thumbnail
        self.thumbWidth = thumbWidth
        self.thumbHeight = thumbHeight
        self.numPages = numPages
        self.tagIDs = tagIDs
        self.lastPage = lastPage
        self.lastRead = lastRead
    }

    var siteKind: SiteKind { SiteKind(rawValue: site) ?? .nhentai }

    var item: GalleryItem {
        var item = GalleryItem(id: gid, mediaID: mediaID, title: title, thumbnail: siteKind == .nhentai ? thumbnail : nil,
                               thumbnailWidth: thumbWidth, thumbnailHeight: thumbHeight,
                               numPages: numPages, tagIDs: tagIDs)
        if siteKind == .ehentai {
            item.site = .ehentai
            item.token = token
            item.absoluteThumb = thumbnail
        }
        return item
    }
}

extension ModelContext {
    /// Records that [gallery] was opened (and, when given, the page read).
    func recordHistory(_ gallery: GalleryDetail, page: Int? = nil) {
        recordHistory(item: gallery.asItem, title: gallery.displayTitle,
                      numPages: gallery.numPages, page: page)
    }

    func recordHistory(item: GalleryItem, title: String, numPages: Int, page: Int? = nil) {
        let key = item.uid
        let descriptor = FetchDescriptor<HistoryEntry>(predicate: #Predicate { $0.key == key })
        if let entry = try? fetch(descriptor).first {
            entry.lastRead = .now
            entry.title = title
            if numPages > 0 { entry.numPages = numPages }
            if !item.tagIDs.isEmpty { entry.tagIDs = item.tagIDs }
            if let page { entry.lastPage = page }
        } else {
            insert(HistoryEntry(
                key: key, gid: item.id, site: item.site.rawValue, token: item.token,
                mediaID: item.mediaID, title: title,
                thumbnail: item.site == .ehentai ? item.absoluteThumb : item.thumbnail, thumbWidth: item.thumbnailWidth,
                thumbHeight: item.thumbnailHeight, numPages: numPages,
                tagIDs: item.tagIDs, lastPage: page ?? 0
            ))
        }
        try? save()
    }

    func lastReadPage(_ gid: Int) -> Int? {
        lastReadPage(key: "nh:\(gid)")
    }

    func lastReadPage(key: String) -> Int? {
        let descriptor = FetchDescriptor<HistoryEntry>(predicate: #Predicate { $0.key == key })
        return (try? fetch(descriptor).first)?.lastPage
    }
}
