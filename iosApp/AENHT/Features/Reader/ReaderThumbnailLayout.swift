import SwiftUI
import UIKit

struct ReaderThumbnailID: Hashable {
    let gallery: String
    let page: Int
}

/// Records a thumbnail's actual frame for the UIKit return transition.
struct ReaderThumbnailAnchor: UIViewRepresentable {
    let id: ReaderThumbnailID

    func makeUIView(context: Context) -> Marker {
        let view = Marker()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: Marker, context: Context) { view.thumbnail = id }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: Marker, context: Context) -> CGSize? {
        if let width = proposal.width, let height = proposal.height {
            return CGSize(width: width, height: height)
        }
        return nil
    }

    final class Marker: UIView {
        var thumbnail: ReaderThumbnailID?

        var isInViewport: Bool {
            guard bounds.width > 1, bounds.height > 1, superview != nil else { return false }
            var ancestor = superview
            while let view = ancestor {
                if let scroll = view as? UIScrollView {
                    let rect = convert(bounds, to: scroll)
                    let visible = scroll.bounds.intersection(rect)
                    if visible.width < rect.width * 0.85 || visible.height < rect.height * 0.85 { return false }
                }
                ancestor = view.superview
            }
            return true
        }
    }
}

/// Keeps the covered gallery able to position its grid synchronously when
/// the reader commits a return, even if SwiftUI has deferred its updates.
struct ReaderThumbnailList: UIViewRepresentable {
    let gallery: String
    let reveal: (Int) -> Void

    func makeUIView(context: Context) -> Marker {
        let view = Marker()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: Marker, context: Context) {
        view.gallery = gallery
        view.reveal = reveal
    }

    static func dismantleUIView(_ view: Marker, coordinator: ()) {
        view.reveal = nil
    }

    final class Marker: UIView {
        var gallery = ""
        var reveal: ((Int) -> Void)?
    }
}

extension UIView {
    func revealReaderThumbnail(_ id: ReaderThumbnailID) {
        if let list = self as? ReaderThumbnailList.Marker, list.gallery == id.gallery {
            list.reveal?(id.page)
        }
        subviews.forEach { $0.revealReaderThumbnail(id) }
    }

    func readerThumbnail(_ id: ReaderThumbnailID) -> UIView? {
        if let marker = self as? ReaderThumbnailAnchor.Marker, marker.thumbnail == id, marker.isInViewport {
            return marker
        }
        for child in subviews {
            if let marker = child.readerThumbnail(id) { return marker }
        }
        return nil
    }
}
