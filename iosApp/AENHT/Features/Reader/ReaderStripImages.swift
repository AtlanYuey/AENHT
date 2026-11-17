import Nuke
import UIKit

enum ReaderStripImages {
    private static let thumbnail = ImageRequest.ThumbnailOptions(
        size: CGSize(width: 1400, height: 8192), unit: .pixels, contentMode: .aspectFit
    )

    static func request(for url: URL, pipeline: ImagePipeline = .shared,
                        priority: ImageRequest.Priority = .normal) -> ImageRequest {
        processedRequest(for: url, pipeline: pipeline, priority: priority, userInfo: [.thumbnailKey: thumbnail])
    }

    static func processedRequest(for url: URL, pipeline: ImagePipeline = .shared,
                                 priority: ImageRequest.Priority = .normal,
                                 processors: [any ImageProcessing] = [],
                                 userInfo: [ImageRequest.UserInfoKey: Any] = [:]) -> ImageRequest {
        if url.isFileURL {
            return ImageRequest(url: url, processors: processors, priority: priority, userInfo: userInfo)
        }

        // The disk prefetcher saves the original URL's bytes, not the thumbnail key.
        // Use the raw-data path for I/O, then let Nuke decode the display thumbnail.
        let original = ImageRequest(url: url, priority: priority)
        var imageInfo = userInfo
        imageInfo[.imageIdKey] = url.absoluteString
        return ImageRequest(
            id: "reader-page:\(url.absoluteString)",
            data: { try await pipeline.data(for: original).0 },
            processors: processors,
            priority: priority,
            // The nested data task owns the I/O slot and the original cache entry.
            options: [.skipDataLoadingQueue, .disableDiskCacheWrites],
            userInfo: imageInfo
        )
    }

    static func cachedAspectRatio(for request: ImageRequest, pipeline: ImagePipeline = .shared) -> CGFloat? {
        guard let cached = pipeline.cache[request], !cached.isPreview else { return nil }
        let size = cached.image.size
        guard size.width > 0, size.height > 0 else { return nil }
        return size.width / size.height
    }
}
