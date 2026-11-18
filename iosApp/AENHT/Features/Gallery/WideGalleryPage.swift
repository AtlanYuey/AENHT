import Foundation

/// The detail page splits only when there is room for both panes.
struct WideGalleryPage {
    static let thumbnailSpacing: CGFloat = 10
    static let thumbnailMinimum: CGFloat = 96
    static let thumbnailInset: CGFloat = 16

    let rightWidth: CGFloat
    let compactThumbnailWidth: CGFloat?

    static func fits(_ size: CGSize) -> Bool {
        size.width >= 960 && size.width > size.height
    }

    init(viewport: CGSize, compactPages: Bool) {
        let half = viewport.width / 2
        if compactPages {
            let available = half - Self.thumbnailInset * 2
            let spacing = Self.thumbnailSpacing
            let count = max(3, floor((available + spacing) / (Self.thumbnailMinimum + spacing)))
            let tile = (available - (count - 1) * spacing) / count
            compactThumbnailWidth = tile
            rightWidth = tile * 3 + spacing * 2 + Self.thumbnailInset * 2
        } else {
            rightWidth = half
            compactThumbnailWidth = nil
        }
    }
}
