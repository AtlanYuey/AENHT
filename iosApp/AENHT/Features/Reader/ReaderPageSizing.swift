import Foundation

enum ReaderPageSizing {
    static let minimumScale: CGFloat = 0.75
    static let maximumScale: CGFloat = 4
    static let normalScale: CGFloat = 1
    static let tolerance: CGFloat = 0.01

    static func clamp(_ scale: CGFloat) -> CGFloat {
        guard scale.isFinite, abs(scale - normalScale) > tolerance else { return normalScale }
        return min(max(scale, minimumScale), maximumScale)
    }

    static func aspectRatio(_ value: CGFloat) -> CGFloat {
        guard value.isFinite, value > 0 else { return 0.7 }
        return value
    }

    /// Fit a whole page if possible. Very tall strips keep a readable column.
    static func width(in viewport: CGSize, aspectRatio: CGFloat, scale: CGFloat) -> CGFloat {
        let width = max(1, viewport.width)
        let readable = min(width, max(280, width * 0.42))
        let fitted = min(width, max(1, viewport.height) * Self.aspectRatio(aspectRatio))
        return max(readable, fitted) * clamp(scale)
    }

    static func travel(viewportWidth: CGFloat, imageWidth: CGFloat) -> CGFloat {
        abs(viewportWidth - imageWidth) / 2
    }
}

struct ReaderSizeStatus: Equatable {
    let adjusted: Bool
    let enlarged: Bool
    let adjusting: Bool

    init(scale: CGFloat = ReaderPageSizing.normalScale, adjusting: Bool = false) {
        let scale = ReaderPageSizing.clamp(scale)
        adjusted = scale != ReaderPageSizing.normalScale
        enlarged = scale > ReaderPageSizing.normalScale
        self.adjusting = adjusting
    }

    var blocksNavigation: Bool { adjusting || enlarged }
}
