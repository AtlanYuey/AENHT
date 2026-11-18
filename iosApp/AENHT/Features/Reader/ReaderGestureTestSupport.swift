#if DEBUG
import Observation
import UIKit

/// Measurements from the real scroll views, made available to the UI test runner.
@MainActor
@Observable
final class ReaderGestureTestSupport {
    static let shared = ReaderGestureTestSupport()
    static let enabled = ProcessInfo.processInfo.arguments.contains("--reader-gesture-test")

    var returnedPage: Int?
    var edgeEvents: [String] = []
    @ObservationIgnored private var overreach: [ObjectIdentifier: CGFloat] = [:]

    static func measure(_ scroll: UIScrollView, page: Int? = nil) {
        guard enabled else { return }
        scroll.isAccessibilityElement = true
        scroll.accessibilityIdentifier = "reader-canvas"
        scroll.accessibilityLabel = "Reader canvas"
        let x = scroll.contentOffset.x + scroll.adjustedContentInset.left
        let y = scroll.contentOffset.y + scroll.adjustedContentInset.top
        let width = max(0, scroll.contentSize.width - scroll.bounds.width)
        let height = max(0, scroll.contentSize.height - scroll.bounds.height)
        let key = ObjectIdentifier(scroll)
        let outside = max(-x, x - width, 0)
        if scroll.isDragging || scroll.isDecelerating {
            shared.overreach[key] = max(shared.overreach[key, default: 0], outside)
        }
        scroll.accessibilityValue = [x, y, width, height, scroll.zoomScale, CGFloat(page ?? -1), shared.overreach[key, default: 0]]
            .map { String(format: "%.3f", Double($0)) }.joined(separator: ",")
    }
}
#endif
