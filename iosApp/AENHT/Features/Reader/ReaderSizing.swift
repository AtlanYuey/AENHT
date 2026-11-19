import CoreGraphics
import Foundation
import UIKit

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

/// What the reader lets the system's own ways out do (its zoom transition's
/// drag that follows the finger back into the thumbnail, the swipe back from
/// the edge): set by the reader, read by its gestures and the navigation controller.
@MainActor
enum ReaderGestureLock {
    /// A page is enlarged: the finger moves it, nothing closes the reader.
    static var enlarged = false
    static var mode: ReadMode = .leftToRight
    /// The zoom transition's own drag closes the reader (found on the reader's screen).
    static var systemCloses: Bool { !systemDrags.isEmpty }
    /// Those drags (they belong to the reader's screen and go with it).
    static var systemDrags: [UIGestureRecognizer] = []
    /// Where a drag starts from the edge (back), not across the pages.
    static let edge: CGFloat = 30

    /// Whether a drag starting at [start] going [velocity] may be the
    /// system's: from the left edge (back), or down in the left-right modes
    /// when Controls › Swipes closes on a swipe down. Never while enlarged,
    /// never across the pages (that turns them), never in the continuous
    /// modes' scrolling.
    static func allowsSystemClose(start: CGPoint, velocity: CGPoint) -> Bool {
        if enlarged { return false }
        if start.x < edge, velocity.x > 0, velocity.x >= abs(velocity.y) { return true }
        guard mode == .leftToRight || mode == .rightToLeft else { return false }
        guard velocity.y > 0, velocity.y > abs(velocity.x) * 1.3 else { return false }
        let settings = AppSettings.shared
        return settings.tapZones == .leftRight && settings.swipeDown == .exit
    }
}

/// Stands in front of the zoom transition's closing drag (which waits for it
/// to fail): it fails, letting that drag follow the finger, only for the
/// drags ReaderGestureLock allows; it takes every other drag (and does
/// nothing with it, alongside the reader's own gestures).
final class ReaderCloseGate: UIPanGestureRecognizer, UIGestureRecognizerDelegate {
    init() {
        super.init(target: nil, action: nil)
        delegate = self
        cancelsTouchesInView = false
        delaysTouchesEnded = false
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let view else { return true }
        let at = location(in: view)
        let moved = translation(in: view)
        let start = CGPoint(x: at.x - moved.x, y: at.y - moved.y)
        return MainActor.assumeIsolated { !ReaderGestureLock.allowsSystemClose(start: start, velocity: velocity(in: view)) }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
