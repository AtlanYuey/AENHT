#if DEBUG
import SwiftUI
import UIKit

/// UI tests only (launched with --reader-fixture): the real reader on local
/// pictures, opened from a grid of thumbnails, and what its scroll views do.
enum ReaderFixture {
    static let isActive = ProcessInfo.processInfo.arguments.contains("--reader-fixture")

    @MainActor
    static func configure(_ settings: AppSettings) {
        let arguments = ProcessInfo.processInfo.arguments
        settings.readMode = arguments.contains("--fixture-strip") ? .vertical : .leftToRight
        settings.doublePage = arguments.contains("--fixture-single") ? .off : .always
        settings.doublePageCover = false
        settings.swipeTurn = arguments.contains("--fixture-sweep") ? .sweep : .slide
        settings.tapTurn = .slide
        settings.allowSmallerPages = true
        settings.keepContinuousPageSize = false
        settings.continuousPageScale = 1
        settings.superResolution = .off
        settings.autoCrop = false
        settings.readerControlsDelay = 10
        settings.doubleTapTopToStart = !arguments.contains("--fixture-no-top-tap")
    }

    static func source() -> ReaderSource {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("reader-fixture", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let size = CGSize(width: 700, height: 1000)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let pages = (0..<12).map { index in
            let url = folder.appendingPathComponent("page-\(index).jpg")
            if !FileManager.default.fileExists(atPath: url.path) {
                let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
                    UIColor(hue: CGFloat(index % 6) / 6, saturation: 0.5, brightness: 0.7, alpha: 1).setFill()
                    context.fill(CGRect(origin: .zero, size: size))
                    UIColor.white.setFill()
                    for line in 1...9 { context.fill(CGRect(x: 40, y: 100 * line, width: 620, height: 8)) }
                }
                try? image.jpegData(compressionQuality: 0.8)?.write(to: url, options: .atomic)
            }
            return ReaderPage(index: index, url: url, aspectRatio: size.width / size.height)
        }
        return ReaderSource(testPages: pages)
    }
}

/// The last scroll view the reader moved: its zoom, offset and how far it can
/// go, and the gestures' states. Shown in a UIKit label on the window, so
/// writing it doesn't make SwiftUI draw the reader again (which would itself
/// lay the pages out again in the middle of a gesture).
@MainActor
final class ReaderDebugMetrics {
    static let shared = ReaderDebugMetrics()
    private var metrics = "none"
    private var events: [String] = []
    private weak var label: UILabel?

    private func show() {
        let text = metrics + " | " + events.joined(separator: ",")
        if label == nil, let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow) {
            let new = UILabel(frame: CGRect(x: 8, y: window.bounds.height - 40, width: window.bounds.width - 16, height: 30))
            new.font = .monospacedSystemFont(ofSize: 9, weight: .regular)
            new.textColor = .yellow
            new.backgroundColor = .black
            new.numberOfLines = 2
            new.isUserInteractionEnabled = false
            new.accessibilityIdentifier = "reader-metrics"
            window.addSubview(new)
            label = new
        }
        label?.superview?.bringSubviewToFront(label!)
        label?.text = text
        label?.accessibilityLabel = text
    }

    func event(_ name: String, _ recognizer: UIGestureRecognizer) {
        guard ReaderFixture.isActive else { return }
        let state: String
        switch recognizer.state {
        case .possible: state = "possible"
        case .began: state = "began"
        case .changed: return
        case .ended: state = "ended"
        case .cancelled: state = "CANCELLED"
        case .failed: state = "failed"
        @unknown default: state = "?"
        }
        note("\(name):\(state)")
    }

    func note(_ value: String) {
        guard ReaderFixture.isActive else { return }
        events.append(value)
        if events.count > 14 { events.removeFirst(events.count - 14) }
        show()
    }

    func record(_ scroll: UIScrollView) {
        guard ReaderFixture.isActive else { return }
        let maxX = max(0, scroll.contentSize.width - scroll.bounds.width)
        let maxY = max(0, scroll.contentSize.height - scroll.bounds.height)
        metrics = String(format: "scale=%.2f x=%.1f y=%.1f maxX=%.1f maxY=%.1f scroll=%d pan=%d",
                         scroll.zoomScale, scroll.contentOffset.x, scroll.contentOffset.y, maxX, maxY,
                         scroll.isScrollEnabled ? 1 : 0, scroll.panGestureRecognizer.isEnabled ? 1 : 0)
        show()
    }
}

struct ReaderFixtureScene: View {
    private let source = ReaderFixture.source()
    @Namespace private var namespace

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(100)), count: 4)) {
                    ForEach(source.pages) { page in
                        NavigationLink(value: ReaderRoute(source: source, page: page.index)) {
                            Color(hue: Double(page.index % 6) / 6, saturation: 0.5, brightness: 0.7)
                                .frame(width: 100, height: 140)
                                .overlay { Text("\(page.index + 1)") }
                                .readerThumbSource(gallery: source.item.transitionKey, page: page.index, namespace)
                        }
                        .accessibilityIdentifier("fixture-page-\(page.index + 1)")
                    }
                }
                .padding()
            }
            .navigationTitle("Reader fixture")
            .navigationDestination(for: ReaderRoute.self) { route in
                ReaderDestination(route: route, namespace: namespace)
            }
        }
    }
}
#endif
