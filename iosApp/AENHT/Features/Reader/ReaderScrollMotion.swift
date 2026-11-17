import Foundation

/// Keep subpixel remainder without retaining an absolute scroll target. UIKit owns the current position.
struct ReaderScrollMotion {
    private var remainder: CGFloat = 0

    mutating func reset() { remainder = 0 }

    mutating func distance(viewport: CGFloat, secondsPerScreen: Double, elapsed: Double, scale: CGFloat) -> CGFloat {
        guard viewport.isFinite, viewport > 0, secondsPerScreen.isFinite, secondsPerScreen > 0,
              elapsed.isFinite, elapsed > 0, scale.isFinite, scale > 0 else { return 0 }
        let pixels = viewport / CGFloat(secondsPerScreen) * CGFloat(min(elapsed, 0.05)) * scale + remainder
        let whole = floor(pixels + 0.000001)
        remainder = max(0, pixels - whole)
        return whole / scale
    }
}
