import Shared
import SwiftUI
import UIKit

/// A command, separate from the observed reading page. Observing a page must never snap to its top.
struct ReaderScrollRequest: Equatable {
    let page: Int
    var animated = false
    private let id = UUID()

    init(page: Int, animated: Bool = false) {
        self.page = page
        self.animated = animated
    }
}

struct ContinuousReader: View {
    let pages: [ReaderPage]
    @Binding var page: Int
    let request: ReaderScrollRequest
    @Binding var running: Bool
    let onInteraction: () -> Void

    @Environment(AppSettings.self) private var settings
    @State private var position = ScrollPosition(idType: Int.self)
    @State private var driver = ContinuousScrollDriver()
    @State private var clock = ReaderFrameClock()
    @State private var retries: [Int: Int] = [:]
    @State private var failedPage: Int?

    var body: some View {
        ScrollView(.vertical) {
            LazyVStack(spacing: settings.readMode == .verticalGap ? settings.pageGap : 0) {
                ForEach(pages) { item in
                    ContinuousReaderPage(item: item, retry: retries[item.index, default: 0]) { state in
                        driver.states[item.index] = state
                        if case .ready = state, failedPage == item.index { failedPage = nil }
                    }
                    .id(item.index)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("readerViewport")) } action: { frame in
                        driver.frames[item.index] = frame
                        // Only the image crossing the top edge changes the recorded page.
                        if frame.minY <= 1, frame.maxY > 1, page != item.index { page = item.index }
                    }
                    .onDisappear {
                        driver.frames.removeValue(forKey: item.index)
                        driver.states.removeValue(forKey: item.index)
                    }
                }
            }
            .scrollTargetLayout()
        }
        .coordinateSpace(name: "readerViewport")
        .scrollPosition($position)
        .scrollIndicators(.hidden)
        .onScrollGeometryChange(for: ContinuousScrollGeometry.self) { geometry in
            ContinuousScrollGeometry(
                offset: max(0, geometry.contentOffset.y + geometry.contentInsets.top),
                size: geometry.containerSize, contentHeight: geometry.contentSize.height
            )
        } action: { old, new in
            driver.geometry = new
            // Hiding the toolbar can change height. Only a width change (rotation / split view)
            // pauses playback here, so starting playback doesn't immediately stop itself.
            if old.size != .zero, abs(old.size.width - new.size.width) > 1 {
                running = false
                driver.targetOffset = nil
            }
        }
        .onScrollPhaseChange { _, phase in
            if phase == .interacting {
                running = false
                driver.targetOffset = nil
                onInteraction()
            }
        }
        .onAppear {
            driver.lastPage = pages.last?.index
            position.scrollTo(id: request.page, anchor: .top)
        }
        .onChange(of: request) { _, new in
            running = false
            driver.targetOffset = nil
            if new.animated {
                withAnimation(.smooth(duration: 0.3)) { position.scrollTo(id: new.page, anchor: .top) }
            } else {
                position.scrollTo(id: new.page, anchor: .top)
            }
        }
        .onChange(of: running) { _, active in
            guard active else { clock.stop(); driver.targetOffset = nil; return }
            guard !pages.isEmpty else { running = false; return }
            failedPage = nil
            clock.start { elapsed in
                guard running else { clock.stop(); return }
                switch driver.advance(secondsPerScreen: settings.autoScrollSecondsPerScreen, elapsed: elapsed) {
                case .wait: break
                case .scroll(let y):
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) { position.scrollTo(y: y) }
                case .finished:
                    running = false
                case .failed(let index):
                    failedPage = index
                    running = false
                }
            }
        }
        .overlay {
            if let failedPage {
                VStack(spacing: 10) {
                    Text("Auto Scroll Paused").font(.subheadline)
                    Button("Retry") {
                        retries[failedPage, default: 0] += 1
                        driver.states[failedPage] = .loading
                        self.failedPage = nil
                    }
                    .buttonStyle(.borderedProminent)
                    .frame(minHeight: 44)
                }
                .padding(20)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            }
        }
        .onDisappear { clock.stop(); running = false }
    }
}

private struct ContinuousReaderPage: View {
    let item: ReaderPage
    let retry: Int
    let onLoad: (RemoteImageLoadState) -> Void
    @State private var loadedRatio: CGFloat?

    private var ratio: CGFloat {
        let value = loadedRatio ?? item.aspectRatio
        return value.isFinite && value > 0 ? value : 0.7
    }

    var body: some View {
        Color.clear
            .aspectRatio(ratio, contentMode: .fit)
            .overlay {
                RemoteImage(url: item.url, contentMode: .fit, targetWidth: nil, retryOnFailure: true,
                            thumbnailSize: CGSize(width: 1400, height: 8192)) { state in
                    if case .ready(let size) = state, size.width > 0, size.height > 0 {
                        loadedRatio = size.width / size.height
                    }
                    onLoad(state)
                }
                .id(retry)
            }
            .onAppear { if item.url == nil { onLoad(.failed) } }
    }
}

private struct ContinuousScrollGeometry: Equatable {
    var offset: CGFloat = 0
    var size: CGSize = .zero
    var contentHeight: CGFloat = 0
}

/// Geometry and load states are deliberately not observable: pixel motion doesn't rebuild the reader chrome.
private final class ContinuousScrollDriver {
    enum Action { case wait, scroll(CGFloat), finished, failed(Int) }
    var geometry = ContinuousScrollGeometry()
    var frames: [Int: CGRect] = [:]
    var states: [Int: RemoteImageLoadState] = [:]
    var lastPage: Int?
    var targetOffset: CGFloat?

    private func isReady(_ index: Int, frame: CGRect) -> Bool {
        guard case .ready(let size) = states[index], size.width > 0, size.height > 0 else { return false }
        // An EH page can initially have a placeholder ratio. Wait for its real height to lay out
        // before declaring a short last page finished or scrolling into a newly loaded strip.
        let expected = frame.width * size.height / size.width
        return abs(frame.height - expected) <= max(1, expected * 0.001)
    }

    func advance(secondsPerScreen: Double, elapsed: Double) -> Action {
        let height = geometry.size.height
        guard height > 0, !frames.isEmpty else { return .wait }
        let distance = CGFloat(ReaderAutoScroll.shared.distance(viewport: Double(height), secondsPerScreen: secondsPerScreen, elapsedSeconds: elapsed))
        var target = (targetOffset ?? geometry.offset) + distance
        let requested = max(0, target - geometry.offset)
        let approaching = frames.filter { $0.value.maxY > 0 && $0.value.minY <= height + requested }
        for (index, frame) in approaching.sorted(by: { $0.key < $1.key }) {
            if isReady(index, frame: frame) { continue }
            target = min(target, geometry.offset + CGFloat(ReaderAutoScroll.shared.beforeUnloadedPage(
                distance: Double(requested), viewportEnd: Double(height), pageTop: Double(frame.minY)
            )))
            if case .failed = states[index] { targetOffset = nil; return .failed(index) }
        }
        if let lastPage, let last = frames[lastPage], last.maxY <= height + 0.5,
           isReady(lastPage, frame: last) {
            targetOffset = nil
            return .finished
        }
        target = min(max(0, geometry.contentHeight - height), max(0, target))
        targetOffset = target
        if abs(target - geometry.offset) < 0.0001 { return .wait }
        return .scroll(target)
    }
}

/// One callback per display refresh; no timers or overlapping scroll animations.
private final class ReaderFrameClock: NSObject {
    private var link: CADisplayLink?
    private var previous: CFTimeInterval?
    private var tick: ((Double) -> Void)?

    func start(_ tick: @escaping (Double) -> Void) {
        stop()
        self.tick = tick
        let link = CADisplayLink(target: self, selector: #selector(frame(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 120)
        self.link = link
        link.add(to: .main, forMode: .common)
    }

    func stop() {
        link?.invalidate()
        link = nil
        previous = nil
        tick = nil
    }

    @objc private func frame(_ link: CADisplayLink) {
        let old = previous
        previous = link.timestamp
        if let old { tick?(link.timestamp - old) }
    }
}
