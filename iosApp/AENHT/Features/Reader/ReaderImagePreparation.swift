import Nuke
import UIKit

struct ReaderImageOptions: Equatable {
    let viewport: CGSize
    let displayScale: CGFloat
    let crop: Bool
    let mode: SuperResolutionMode
    let model: SuperResolutionModel

    func enhancement(for url: URL, pagesPerTurn: Int = 1, continuousViewport: CGSize? = nil,
                     continuousScale: CGFloat = 1, zoomAspectRatio: CGFloat? = nil) -> ReaderImageEnhancement? {
        guard mode != .off, viewport.width > 1, viewport.height > 1 else { return nil }
        let target: ReaderImageEnhancement.Target
        if let continuousViewport {
            target = .strip(viewport: continuousViewport, displayScale: displayScale, scale: continuousScale,
                            zoomAspectRatio: zoomAspectRatio)
        } else if pagesPerTurn == 2 {
            target = .fixed(CGSize(width: max(1, (viewport.width - 6 * displayScale) / 2), height: viewport.height))
        } else {
            target = .fixed(viewport)
        }
        let style = continuousViewport != nil ? "|strip" : (crop ? "|crop" : "")
        return ReaderImageEnhancement(key: url.absoluteString + style, target: target,
                                      mode: mode, model: model)
    }
}

struct ReaderImageEnhancement: Equatable {
    enum Target: Equatable {
        case fixed(CGSize)
        case strip(viewport: CGSize, displayScale: CGFloat, scale: CGFloat, zoomAspectRatio: CGFloat?)
    }

    let key: String
    let target: Target
    let mode: SuperResolutionMode
    let model: SuperResolutionModel
    private func target(for image: UIImage) -> CGSize? {
        switch target {
        case .fixed(let size): return size
        case let .strip(viewport, displayScale, scale, zoomAspectRatio):
            // Decode first, so the displayed and prepared strip use the same aspect ratio.
            guard mode != .auto || !ReaderStripImages.mayBeDownsampled(image) else { return nil }
            let ratio = image.size.width / max(1, image.size.height)
            let width = ReaderPageSizing.stripWidth(in: viewport, aspectRatio: ratio, scale: scale,
                                                   zoomAspectRatio: zoomAspectRatio)
            return CGSize(width: width * displayScale, height: .greatestFiniteMagnitude)
        }
    }

    func cached(_ image: UIImage) -> UIImage? {
        guard let target = target(for: image) else { return nil }
        return SuperResolver.shared.cached(image, key: key, target: target, mode: mode, kind: model)
    }

    func enhance(_ image: UIImage, ahead: Bool = false) async -> UIImage? {
        guard let target = target(for: image) else { return nil }
        return await SuperResolver.shared.enhance(image, key: key, target: target, mode: mode, kind: model, ahead: ahead)
    }
}

/// Prepares two upcoming page turns (up to four distinct images) in the same caches as the reader.
@MainActor
final class ReaderImagePreparation {
    static let turns = 2

    struct Item {
        let request: ImageRequest
        let enhancement: ReaderImageEnhancement?
    }

    private let pipeline: ImagePipeline
    private let prefetcher: ImagePrefetcher
    private var upcoming: [Item] = []
    private var keys: [ImageCacheKey] = []
    private var enhancementTask: Task<Void, Never>?

    init(pipeline: ImagePipeline = .shared) {
        self.pipeline = pipeline
        prefetcher = ImagePrefetcher(pipeline: pipeline, destination: .memoryCache, maxConcurrentRequestCount: 1)
        prefetcher.didComplete = { [weak self] in self?.prepareEnhancements() }
    }

    func prepare(_ items: [Item]) {
        var seen: Set<ImageCacheKey> = []
        let next = items.filter { seen.insert(pipeline.cache.makeImageCacheKey(for: $0.request)).inserted }
        let nextKeys = next.map { pipeline.cache.makeImageCacheKey(for: $0.request) }
        guard nextKeys != keys || next.map(\.enhancement) != upcoming.map(\.enhancement) else { return }
        enhancementTask?.cancel()
        let stale = zip(keys, upcoming).filter { !seen.contains($0.0) }.map { $0.1.request }
        prefetcher.stopPrefetching(with: stale)
        upcoming = next
        keys = nextKeys
        let missing = next.filter { pipeline.cache[$0.request]?.isPreview != false }
        if missing.isEmpty {
            prepareEnhancements()
        } else {
            prefetcher.startPrefetching(with: missing.map(\.request))
        }
    }

    func stop() {
        prefetcher.stopPrefetching()
        enhancementTask?.cancel()
        enhancementTask = nil
        upcoming = []
        keys = []
    }

    private func prepareEnhancements() {
        enhancementTask?.cancel()
        let items = upcoming
        let pipeline = pipeline
        enhancementTask = Task(priority: .utility) {
            for item in items {
                guard !Task.isCancelled else { return }
                guard let enhancement = item.enhancement,
                      let original = pipeline.cache[item.request], !original.isPreview else { continue }
                _ = await enhancement.enhance(original.image, ahead: true)
            }
        }
    }

    deinit { enhancementTask?.cancel() }
}
