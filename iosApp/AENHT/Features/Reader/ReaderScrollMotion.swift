import Foundation

/// UIKit retains fractional offsets. Rounding each frame creates alternating short/long steps.
enum ReaderScrollMotion {
    static func distance(viewport: CGFloat, secondsPerScreen: Double, elapsed: Double) -> CGFloat {
        guard viewport.isFinite, viewport > 0, secondsPerScreen.isFinite, secondsPerScreen > 0,
              elapsed.isFinite, elapsed > 0 else { return 0 }
        return viewport / CGFloat(secondsPerScreen) * CGFloat(min(elapsed, 0.05))
    }
}
