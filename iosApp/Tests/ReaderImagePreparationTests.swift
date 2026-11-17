import Nuke
import UIKit
import XCTest

@MainActor
final class ReaderImagePreparationTests: XCTestCase {
    func testStripThumbnailReusesPrefetchedDiskData() async throws {
        let url = try XCTUnwrap(URL(string: "https://reader-test.invalid/strip.png"))
        let data = try makeImageData()
        let loader = FixtureDataLoader(data: data)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let cache = try DataCache(path: directory)
        defer { try? FileManager.default.removeItem(at: directory) }
        let pipeline = ImagePipeline {
            $0.dataLoader = loader
            $0.dataCache = cache
            $0.imageCache = ImageCache(costLimit: 32 * 1024 * 1024)
            $0.dataLoadingQueue.maxConcurrentOperationCount = 1
        }

        let original = ImageRequest(url: url)
        _ = try await pipeline.data(for: original)
        cache.flush()
        XCTAssertTrue(pipeline.cache.containsData(for: original))
        XCTAssertEqual(loader.loadCount, 1)

        let displayRequest = ReaderStripImages.request(for: url, pipeline: pipeline)
        XCTAssertNil(pipeline.cache[displayRequest], "Downloading alone has not decoded the strip")
        var oldRequest = ImageRequest(url: url)
        oldRequest.userInfo[.thumbnailKey] = ImageRequest.ThumbnailOptions(
            size: CGSize(width: 1400, height: 8192), unit: .pixels, contentMode: .aspectFit
        )
        XCTAssertNotEqual(pipeline.cache.makeDataCacheKey(for: original), pipeline.cache.makeDataCacheKey(for: oldRequest))
        XCTAssertFalse(pipeline.cache.containsData(for: oldRequest))

        let prepare = ReaderStripImages.request(for: url, pipeline: pipeline, priority: .low)
        let image = try await pipeline.image(for: prepare)
        XCTAssertEqual(loader.loadCount, 1, "Decoding already downloaded bytes must not fetch the page again")
        XCTAssertTrue(pipeline.cache[displayRequest]?.image === image)
        XCTAssertEqual(ReaderStripImages.cachedAspectRatio(for: displayRequest, pipeline: pipeline), 0.5)
    }

    func testPreparedDownloadedFileIsAFirstFrameCacheHit() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        try makeImageData().write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let pipeline = ImagePipeline { $0.imageCache = ImageCache(costLimit: 32 * 1024 * 1024) }
        let request = ReaderStripImages.request(for: file, pipeline: pipeline, priority: .low)
        let decoded = try await pipeline.image(for: request)
        let display = ReaderStripImages.request(for: file, pipeline: pipeline)
        XCTAssertTrue(pipeline.cache[display]?.image === decoded)
    }

    private func makeImageData() throws -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 96, height: 192), format: format).image { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 96, height: 192))
        }
        return try XCTUnwrap(image.pngData())
    }
}

private final class FixtureDataLoader: DataLoading, @unchecked Sendable {
    private final class Token: Cancellable { func cancel() {} }
    private let data: Data
    private let lock = NSLock()
    private var loads = 0

    init(data: Data) { self.data = data }

    var loadCount: Int { lock.withLock { loads } }

    func loadData(with request: URLRequest, didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
                  completion: @escaping @Sendable (Error?) -> Void) -> any Cancellable {
        lock.withLock { loads += 1 }
        didReceiveData(data, URLResponse(url: request.url!, mimeType: "image/png", expectedContentLength: data.count, textEncodingName: nil))
        completion(nil)
        return Token()
    }
}
