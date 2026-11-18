#if DEBUG
import SwiftUI
import UIKit

/// Exercises the shipping reader with local, non-network sample images.
struct ReaderGestureTestScene: View {
    private let source = ReaderGestureTestScene.sample()
    @Namespace private var namespace
    @State private var position: Int?
    @State private var progress = ReaderProgress.shared
    @State private var results = ReaderGestureTestSupport.shared

    static func configure(_ settings: AppSettings) {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--reader-gaps") {
            settings.readMode = .verticalGap
        } else if arguments.contains("--reader-strip") {
            settings.readMode = .vertical
        } else {
            settings.readMode = .leftToRight
        }
        settings.doublePage = .always
        settings.doublePageCover = false
        settings.doublePageSingleStep = false
        settings.keepContinuousPageSize = true
        settings.continuousPageScale = 1
        settings.swipeFades = false
        settings.superResolution = .off
        settings.autoCrop = false
        settings.readerControlsDelay = 10
    }

    var body: some View {
        NavigationStack {
            HStack(spacing: 0) {
                VStack(alignment: .leading) {
                    Text("Reader gesture fixture").accessibilityIdentifier("reader-gallery")
                    Text("Current thumbnail \((progress.point(gallery: source.item.transitionKey)?.page ?? -1) + 1)")
                        .accessibilityIdentifier("reader-gallery-position")
                    Text("Return target \((results.returnedPage ?? -1) + 1)")
                        .accessibilityIdentifier("reader-return-target")
                    Text(results.edgeEvents.joined(separator: ","))
                        .accessibilityIdentifier("reader-edge-events")
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(92)), count: 3)) {
                        ForEach(source.pages) { page in
                            NavigationLink(value: ReaderRoute(source: source, page: page.index)) {
                                Color(hue: Double(page.index % 8) / 8, saturation: 0.56, brightness: 0.72)
                                    .frame(width: 92, height: 132)
                                    .overlay { Text(String(page.index + 1)).foregroundStyle(.white) }
                                    .readerThumbnailSource(gallery: source.item.transitionKey, page: page.index, in: namespace)
                            }
                            .accessibilityIdentifier("reader-thumbnail-\(page.index + 1)")
                            .id(page.index)
                        }
                    }
                    .scrollTargetLayout()
                    .padding(12)
                }
                .followsReader(gallery: source.item.transitionKey, position: $position)
                .frame(width: 328)
            }
            .navigationTitle("Reader gesture fixture")
            .navigationDestination(for: ReaderRoute.self) { route in
                ReaderDestination(route: route, namespace: namespace)
            }
        }
    }

    private static func sample() -> ReaderSource {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("reader-gesture-fixture", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let size = CGSize(width: 640, height: 920)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let pages = (0..<30).map { index in
            let url = folder.appendingPathComponent("page-\(index).jpg")
            if !FileManager.default.fileExists(atPath: url.path) {
                let artwork = UIGraphicsImageRenderer(size: size, format: format).image { context in
                    let color = UIColor(hue: CGFloat(index % 8) / 8, saturation: 0.58, brightness: 0.68, alpha: 1)
                    color.setFill()
                    context.fill(CGRect(origin: .zero, size: size))
                    UIColor.white.setFill()
                    for line in 1...8 {
                        context.fill(CGRect(x: 40, y: 100 * line, width: 560, height: 6))
                    }
                }
                try? artwork.jpegData(compressionQuality: 0.8)?.write(to: url, options: .atomic)
            }
            return ReaderPage(index: index, url: url, aspectRatio: size.width / size.height)
        }
        return ReaderSource(testPages: pages)
    }
}
#endif
