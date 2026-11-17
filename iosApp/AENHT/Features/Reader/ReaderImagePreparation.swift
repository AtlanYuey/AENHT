import Nuke
import UIKit

struct ReaderImageOptions: Equatable {
    let viewport: CGSize
    let displayScale: CGFloat
    let crop: Bool
    let mode: SuperResolutionMode
    let model: SuperResolutionModel

    func enhancement(for url: URL, continuous: Bool = false, pagesPerTurn: Int = 1) -> ReaderImageEnhancement? {
        guard mode != .off, viewport.width > 1, viewport.height > 1 else { return nil }
        let target: CGSize
        if continuous {
            // A strip fits the viewport width and continues below the screen.
            target = CGSize(width: viewport.width, height: .greatestFiniteMagnitude)
        } else if pagesPerTurn == 2 {
            target = CGSize(width: max(1, (viewport.width - 6 * displayScale) / 2), height: viewport.height)
        } else {
            target = viewport
        }
        let style = continuous ? "|strip" : (crop ? "|crop" : "")
        return ReaderImageEnhancement(key: url.absoluteString + style, target: target,
                                      mode: mode, model: model, stripThumbnail: continuous)
    }
}

struct ReaderImageEnhancement: Equatable {
    let key: String
    let target: CGSize
    let mode: SuperResolutionMode
    let model: SuperResolutionModel
    let stripThumbnail: Bool

    private func eligible(_ image: UIImage) -> Bool {
        // Automatic mode should not sharpen a large source just because its
        // strip preview was reduced to the decoder's memory limit.
        mode != .auto || !stripThumbnail || !ReaderStripImages.mayBeDownsampled(image)
    }

    func cached(_ image: UIImage) -> UIImage? {
        guard eligible(image) else { return nil }
        return SuperResolver.shared.cached(image, key: key, target: target, mode: mode, kind: model)
    }

    func enhance(_ image: UIImage, ahead: Bool = false) async -> UIImage? {
        guard eligible(image) else { return nil }
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
        prefetcher.startPrefetching(with: next.map(\.request))
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
