import SwiftUI
import UIKit

/// Drives pixel scrolling and preserves the reading point across image size changes.
@MainActor
final class ReaderScrollDriver: NSObject, UIGestureRecognizerDelegate {
    enum StopReason { case finished, failed(Int), interaction, resized }
    struct ResizeAnchor {
        let index: Int
        let fraction: CGFloat
        let aspectRatio: CGFloat
        let viewportY: CGFloat
        let horizontalFraction: CGFloat
    }

    private struct PendingResize {
        let anchor: ResizeAnchor
        let width: CGFloat
        let viewportY: CGFloat
        let canvasWidth: CGFloat?
        let horizontalFraction: CGFloat
        var requestedRow = false
    }
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
    var onPinch: ((UIPinchGestureRecognizer, CGFloat) -> Void)?
    var onMissingResizeRow: ((Int) -> Void)?
    private(set) var running = false
    private(set) var knownRatios: [Int: CGFloat] = [:]
    private weak var scrollView: UIScrollView?
    private var anchors: [Int: Anchor] = [:]
    private var images: [Int: ImageStatus] = [:]
    private var motion = ReaderScrollMotion()
    private var displayLink: CADisplayLink?
    private var previousTimestamp: CFTimeInterval?
    private var tickTarget: TickTarget?
    private var lastReportedPage: Int?
    private var lastVisiblePages: ClosedRange<Int>?
    private var reportScheduled = false
    private var viewport: CGSize = .zero
    private var pendingResize: PendingResize?
    var restoringRow: Int? { pendingResize?.anchor.index }
    private var originalPanTouches: Int?
    private lazy var pinch = UIPinchGestureRecognizer(target: self, action: #selector(didPinch(_:)))

    func register(index: Int, view: UIView) {
        if anchors[index]?.view !== view { anchors[index] = Anchor(view) }
        var ancestor = view.superview
        while let current = ancestor {
            if let scroll = current as? UIScrollView {
                connect(to: scroll)
                break
            }
            ancestor = current.superview
        }
        scheduleReport()
    }

    private func scheduleReport() {
        // Registration occurs during SwiftUI layout; publish after that layout.
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
        onPinch = nil
        onMissingResizeRow = nil
        pendingResize = nil
        detachGestures()
    }

    private func connect(to scroll: UIScrollView) {
        guard scrollView !== scroll else { return }
        detachGestures()
        scrollView = scroll
        originalPanTouches = scroll.panGestureRecognizer.maximumNumberOfTouches
        scroll.panGestureRecognizer.maximumNumberOfTouches = 1
        pinch.delegate = self
        scroll.addGestureRecognizer(pinch)
    }

    private func detachGestures() {
        if let scroll = scrollView {
            if let originalPanTouches { scroll.panGestureRecognizer.maximumNumberOfTouches = originalPanTouches }
            scroll.removeGestureRecognizer(pinch)
        }
        scrollView = nil
        originalPanTouches = nil
    }

    @objc private func didPinch(_ gesture: UIPinchGestureRecognizer) {
        guard let scroll = scrollView else { return }
        onPinch?(gesture, gesture.location(in: scroll).y - scroll.bounds.minY)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        other === scrollView?.panGestureRecognizer
    }

    func resizeAnchor(at viewportY: CGFloat? = nil) -> ResizeAnchor? {
        guard let scroll = scrollView else { return nil }
        let y = min(max(0, viewportY ?? scroll.bounds.height / 2), scroll.bounds.height)
        let contentY = scroll.bounds.minY + y
        guard let row = frames(in: scroll).min(by: {
            distance(from: contentY, to: $0.rect) < distance(from: contentY, to: $1.rect)
        }) else { return nil }
        let range = horizontalRange(in: scroll)
        let x = scroll.contentOffset.x + scroll.adjustedContentInset.left
        let horizontalFraction = range > 0 ? min(max(x / range, 0), 1) : 0.5
        return ResizeAnchor(index: row.index,
                            fraction: min(max((contentY - row.rect.minY) / row.rect.height, 0), 1),
                            aspectRatio: row.rect.width / row.rect.height, viewportY: y,
                            horizontalFraction: horizontalFraction)
    }

    private func distance(from y: CGFloat, to rect: CGRect) -> CGFloat {
        max(0, max(rect.minY - y, y - rect.maxY))
    }

    func preserve(_ anchor: ResizeAnchor, width: CGFloat, viewportY: CGFloat? = nil,
                  canvasWidth: CGFloat? = nil, centerHorizontally: Bool = false) {
        let requested = pendingResize?.anchor.index == anchor.index && pendingResize?.requestedRow == true
        pendingResize = PendingResize(anchor: anchor, width: width, viewportY: viewportY ?? anchor.viewportY,
                                      canvasWidth: canvasWidth,
                                      horizontalFraction: centerHorizontally ? 0.5 : anchor.horizontalFraction,
                                      requestedRow: requested)
        scheduleReport()
    }

    func cancelResize() { pendingResize = nil }

    private func horizontalRange(in scroll: UIScrollView) -> CGFloat {
        let insets = scroll.adjustedContentInset
        return max(0, scroll.contentSize.width - scroll.bounds.width + insets.left + insets.right)
    }

    private func restorePosition(in scroll: UIScrollView, rows: [(index: Int, rect: CGRect)]) -> Bool {
        guard var pending = pendingResize else { return true }
        guard let row = rows.first(where: { $0.index == pending.anchor.index }) else {
            if !pending.requestedRow {
                pending.requestedRow = true
                pendingResize = pending
                let index = pending.anchor.index
                DispatchQueue.main.async { [weak self] in self?.onMissingResizeRow?(index) }
            }
            return false
        }
        // A newly decoded image can replace the aspect ratio during a pinch.
        let observedAspect = row.rect.width / row.rect.height
        guard abs(row.rect.width - pending.width) < 1 || abs(observedAspect - pending.anchor.aspectRatio) > 0.01 else {
            return false
        }
        if let width = pending.canvasWidth, abs(scroll.contentSize.width - width) > 1 { return false }
        pendingResize = nil
        let minimum = -scroll.adjustedContentInset.top
        let maximum = max(minimum, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        let y = row.rect.minY + row.rect.height * pending.anchor.fraction - pending.viewportY
        let x = pending.canvasWidth == nil ? scroll.contentOffset.x
            : horizontalRange(in: scroll) * pending.horizontalFraction - scroll.adjustedContentInset.left
        let target = CGPoint(x: x, y: min(max(y, minimum), maximum))
        if abs(target.y - scroll.contentOffset.y) > 0.5 || abs(target.x - scroll.contentOffset.x) > 0.5 {
            scroll.setContentOffset(target, animated: false)
        }
        return true
    }

    func userInteractionStarted() {
        motion.reset()
        if running, !continueOnDrag { finish(.interaction) }
    }

    func viewportChanged(_ size: CGSize) {
        let widthChanged = viewport.width > 0 && abs(viewport.width - size.width) > 1
        if widthChanged || abs(viewport.height - size.height) > 1 {
            cancelResize()
        }
        // Showing the controls can change the height without changing the reading position.
        if widthChanged, running { finish(.resized) }
        viewport = size
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
        let rows = frames(in: scroll)
        guard restorePosition(in: scroll, rows: rows) else { return }
        let top = scroll.contentOffset.y + scroll.adjustedContentInset.top
        let bottom = scroll.contentOffset.y + scroll.bounds.height - scroll.adjustedContentInset.bottom
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
