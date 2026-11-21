import SwiftUI
import UIKit

/// Owns only automatic pixel motion. Explicit page jumps continue to use ScrollViewReader.
@MainActor
final class ReaderScrollDriver: NSObject {
    enum StopReason { case finished, failed(Int), interaction, resized }
    private enum ImageStatus { case loading, ready(CGSize), failed }
    private final class Anchor {
        weak var view: UIView?
        init(_ view: UIView) { self.view = view }
    }
    @MainActor private final class TickTarget: NSObject {
        weak var owner: ReaderScrollDriver?
        @objc func tick(_ link: CADisplayLink) { owner?.displayFrame(link) }
    }

    var secondsPerScreen = 10.0
    var continueOnDrag = false
    var lastPage: Int?
    var onPage: ((Int) -> Void)?
    var onVisiblePages: ((ClosedRange<Int>) -> Void)?
    var onStop: ((StopReason) -> Void)?
    private(set) var running = false
    private(set) var knownRatios: [Int: CGFloat] = [:]
    private weak var scrollView: UIScrollView?
    /// The strip's scroll view, once its pages are laid out (the reader's pinch is added to it).
    var scroll: UIScrollView? { scrollView }
    private var anchors: [Int: Anchor] = [:]
    private var images: [Int: ImageStatus] = [:]
    private var motion = ReaderScrollMotion()
    private var displayLink: CADisplayLink?
    private var previousTimestamp: CFTimeInterval?
    private var tickTarget: TickTarget?
    private var lastReportedPage: Int?
    private var lastVisiblePages: ClosedRange<Int>?
    private var reportScheduled = false
    private var viewportWidth: CGFloat = 0

    func register(index: Int, view: UIView) {
        if anchors[index]?.view !== view { anchors[index] = Anchor(view) }
        var ancestor = view.superview
        while let current = ancestor {
            if let scroll = current as? UIScrollView {
                scrollView = scroll
                // A tap on the status bar doesn't scroll the strip to the top (ReaderTopTaps has it).
                if scroll.scrollsToTop { scroll.scrollsToTop = false }
                break
            }
            ancestor = current.superview
        }
        // Registration occurs during SwiftUI layout; publish the page on the next main-loop turn.
        guard !running, !reportScheduled else { return }
        reportScheduled = true
        DispatchQueue.main.async { [weak self] in
            self?.reportScheduled = false
            if self?.running == false { self?.reportPage() }
        }
    }

    func unregister(index: Int, view: UIView) {
        guard anchors[index]?.view === view else { return }
        anchors.removeValue(forKey: index)
        images.removeValue(forKey: index)
        // A recycled lazy row keeps its learned height when it appears again.
    }

    func image(index: Int, size: CGSize? = nil, failed: Bool = false) {
        if let size, size.width > 0, size.height > 0 {
            knownRatios[index] = size.width / size.height
            images[index] = .ready(size)
        } else {
            images[index] = failed ? .failed : .loading
        }
    }

    func start() {
        stop()
        running = true
        let target = TickTarget()
        target.owner = self
        tickTarget = target
        let link = CADisplayLink(target: target, selector: #selector(TickTarget.tick(_:)))
        let maximum = Float(scrollView?.window?.screen.maximumFramesPerSecond ?? 60)
        link.preferredFrameRateRange = CAFrameRateRange(minimum: min(30, maximum), maximum: maximum, preferred: maximum)
        displayLink = link
        link.add(to: .main, forMode: .common)
        reportPage()
    }

    func stop() {
        running = false
        displayLink?.invalidate()
        displayLink = nil
        tickTarget = nil
        previousTimestamp = nil
        motion.reset()
    }

    func disconnect() {
        stop()
        onPage = nil
        onVisiblePages = nil
        lastVisiblePages = nil
        lastReportedPage = nil
        onStop = nil
    }

    func userInteractionStarted() {
        motion.reset()
        if running, !continueOnDrag { finish(.interaction) }
    }

    func viewportChanged(_ size: CGSize) {
        if viewportWidth > 0, abs(viewportWidth - size.width) > 1, running { finish(.resized) }
        viewportWidth = size.width
        // During playback the display link publishes progress once, after moving the actual view.
        // Geometry callbacks for the same offset must not repeat the coordinate scans.
        if !running { reportPage() }
    }

    private func frames(in scroll: UIScrollView) -> [(index: Int, rect: CGRect)] {
        anchors.compactMap { index, anchor in
            guard let view = anchor.view, view.bounds.width > 0, view.bounds.height > 0,
                  view.isDescendant(of: scroll) else { return nil }
            return (index, view.convert(view.bounds, to: scroll))
        }.sorted { $0.index < $1.index }
    }

    func reportPage() {
        guard let scroll = scrollView else { return }
        let top = scroll.contentOffset.y + scroll.adjustedContentInset.top
        let bottom = scroll.contentOffset.y + scroll.bounds.height - scroll.adjustedContentInset.bottom
        let rows = frames(in: scroll)
        func isVisible(_ row: (index: Int, rect: CGRect)) -> Bool {
            row.rect.maxY > top + 0.5 && row.rect.minY < bottom
        }
        guard let first = rows.first(where: isVisible) else { return }
        if first.index != lastReportedPage {
            lastReportedPage = first.index
            onPage?(first.index)
        }
        if let onVisiblePages, let last = rows.last(where: isVisible) {
            let visible = first.index...last.index
            if visible != lastVisiblePages {
                lastVisiblePages = visible
                onVisiblePages(visible)
            }
        }
    }

    private func ready(_ index: Int, rect: CGRect) -> Bool {
        guard case .ready(let size) = images[index] else { return false }
        let expected = rect.width * size.height / size.width
        return abs(rect.height - expected) <= max(1, expected * 0.001)
    }

    /// Exposed internally for deterministic UIKit tests. Positions come from UIKit on every frame.
    func advance(elapsed: Double, scale suppliedScale: CGFloat? = nil) {
        guard running, let scroll = scrollView else { return }
        defer { reportPage() }
        if scroll.isTracking || scroll.isDragging || scroll.isDecelerating {
            motion.reset()
            if !continueOnDrag, scroll.isDragging || scroll.isDecelerating { finish(.interaction) }
            return
        }
        let inset = scroll.adjustedContentInset
        let height = scroll.bounds.height - inset.top - inset.bottom
        let top = scroll.contentOffset.y + inset.top
        let bottom = scroll.contentOffset.y + scroll.bounds.height - inset.bottom
        let scale = suppliedScale ?? scroll.window?.screen.scale ?? scroll.traitCollection.displayScale
        let rows = frames(in: scroll)
        guard height > 0, !rows.isEmpty else { motion.reset(); return }
        var distance = motion.distance(viewport: height, secondsPerScreen: secondsPerScreen, elapsed: elapsed)
        for row in rows where row.rect.maxY > top && row.rect.minY <= bottom + distance {
            if case .failed = images[row.index] { finish(.failed(row.index)); return }
            if !ready(row.index, rect: row.rect) {
                let limited = min(distance, max(0, row.rect.minY - bottom))
                if limited < distance { motion.limit(to: limited) }
                distance = limited
            }
        }
        if let last = rows.first(where: { $0.index == lastPage }), ready(last.index, rect: last.rect),
           last.rect.maxY <= bottom + 0.5 / max(1, scale) {
            finish(.finished)
            return
        }
        let actual = scroll.contentOffset.y
        let maximum = max(-inset.top, scroll.contentSize.height - scroll.bounds.height + inset.bottom)
        let next = min(maximum, actual + distance)
        // Lazy layout may correct content size/offset. Never fight it by writing an old absolute target.
        guard distance > 0 else { motion.moved(by: 0, scale: scale); return }
        guard next > actual else { motion.reset(); return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: next), animated: false)
        CATransaction.commit()
        motion.moved(by: scroll.contentOffset.y - actual, scale: scale)
    }

    private func displayFrame(_ link: CADisplayLink) {
        let old = previousTimestamp
        previousTimestamp = link.targetTimestamp
        if let old { advance(elapsed: link.targetTimestamp - old) }
    }

    private func finish(_ reason: StopReason) {
        stop()
        onStop?(reason)
    }

    deinit { displayLink?.invalidate() }
}

/// A non-interactive row anchor; uses public UIView coordinates, not a replacement scroll-view delegate.
struct ReaderPageAnchor: UIViewRepresentable {
    let index: Int
    let driver: ReaderScrollDriver

    func makeUIView(context: Context) -> ReaderPageAnchorView {
        let view = ReaderPageAnchorView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.driver = driver
        view.index = index
        return view
    }

    func updateUIView(_ view: ReaderPageAnchorView, context: Context) {
        if view.index != index { view.driver?.unregister(index: view.index, view: view) }
        view.driver = driver
        view.index = index
        driver.register(index: index, view: view)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ReaderPageAnchorView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }

    static func dismantleUIView(_ view: ReaderPageAnchorView, coordinator: ()) {
        view.driver?.unregister(index: view.index, view: view)
    }
}

final class ReaderPageAnchorView: UIView {
    weak var driver: ReaderScrollDriver?
    var index = 0
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { driver?.register(index: index, view: self) }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        driver?.register(index: index, view: self)
    }
}
