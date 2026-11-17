import Foundation

/// Request continuous motion and carry only UIKit's subpixel rounding error, never an absolute target.
struct ReaderScrollMotion {
    private var remainder: CGFloat = 0
    private var pending: CGFloat = 0

    mutating func reset() { remainder = 0; pending = 0 }

    mutating func distance(viewport: CGFloat, secondsPerScreen: Double, elapsed: Double) -> CGFloat {
        guard viewport.isFinite, viewport > 0, secondsPerScreen.isFinite, secondsPerScreen > 0,
              elapsed.isFinite, elapsed > 0 else { reset(); return 0 }
        pending = viewport / CGFloat(secondsPerScreen) * CGFloat(min(elapsed, 0.05)) + remainder
        return max(0, pending)
    }

    /// Loading boundaries discard unread distance, so it is not caught up after an image loads.
    mutating func limit(to distance: CGFloat) { pending = distance }

    mutating func moved(by actual: CGFloat, scale: CGFloat) {
        let error = pending - actual
        let pixel = 1 / max(1, scale)
        // An image layout correction is not a rounding error: do not push against it next frame.
        remainder = error.isFinite && abs(error) <= pixel + 0.000001 ? error : 0
        pending = 0
    }
}
