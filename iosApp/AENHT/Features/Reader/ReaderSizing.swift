import CoreGraphics
import Foundation

/// A page's size against its usual one: fitted, pinched smaller, or enlarged.
enum ReaderZoom: Equatable {
    case fitted, smaller, enlarged

    init(scale: CGFloat) {
        if scale > 1.01 {
            self = .enlarged
        } else if scale < 0.99 {
            self = .smaller
        } else {
            self = .fitted
        }
    }

    /// Paged modes: never smaller than half the fitted size...
    static let smallestPaged: CGFloat = 0.5
    /// ...nor narrower than this (points): smaller doesn't read.
    static let readableWidth: CGFloat = 300

    /// The smallest zoom of a page fitted at [fitted] (points).
    static func minimumPaged(fitted: CGSize) -> CGFloat {
        guard fitted.width > 0 else { return smallestPaged }
        return min(1, max(smallestPaged, readableWidth / fitted.width))
    }
}

/// The continuous modes' page width: by default a whole page shows on the
/// screen (as wide as fits with its height on screen, at most the screen's
/// width); pinched, a [scale] of that.
enum ContinuousSizing {
    static let maximumScale: CGFloat = 4

    /// The default width of the pages on a screen of [viewport], for pages of [ratio] (width / height).
    static func baseWidth(viewport: CGSize, ratio: CGFloat) -> CGFloat {
        guard viewport.width > 0, viewport.height > 0 else { return viewport.width }
        let fitsHeight = viewport.height * min(max(ratio, 0.3), 2)
        return min(viewport.width, fitsHeight)
    }

    /// The smallest scale: the pages stay readable (300 pt, and 60 % of the default at least).
    static func minimumScale(base: CGFloat) -> CGFloat {
        guard base > 0 else { return 1 }
        return min(1, max(0.6, ReaderZoom.readableWidth / base))
    }

    static func clamp(_ scale: CGFloat, base: CGFloat) -> CGFloat {
        min(max(scale, minimumScale(base: base)), maximumScale)
    }
}

/// While a page is enlarged, the swipe back from the edge waits (it would
/// move the page instead): set by the reader, read by the navigation controller.
@MainActor
enum ReaderGestureLock {
    static var enlarged = false
}
