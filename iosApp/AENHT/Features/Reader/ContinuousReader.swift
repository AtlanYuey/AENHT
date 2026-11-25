import SwiftUI
import UIKit
import Nuke

/// A command, separate from observed reading progress. Only an explicit jump scrolls to an ID.
struct ReaderScrollRequest: Equatable {
    let page: Int
    var animated = false
    /// Scroll this many screens (down; up when negative) from where the strip is, instead (keys).
    var screens = 0
    private let id = UUID()
    init(page: Int, animated: Bool = false, screens: Int = 0) { self.page = page; self.animated = animated; self.screens = screens }
}

struct ContinuousReader: View {
    let pages: [ReaderPage]
    let imageOptions: ReaderImageOptions
    @Binding var page: Int
    let request: ReaderScrollRequest
    @Binding var running: Bool
    /// The pages' width against the default one (a whole page on screen), pinched with two fingers.
    @Binding var scale: CGFloat
    /// The strip is narrower or wider than the screen: a sideways drag moves it.
    @Binding var movable: Bool
    /// Counts the reader's Reset Size taps: back to the default width.
    let resetRequest: Int
    /// Pages reloaded (Reload Page in a page's menu): how many times each.
    var reloads: [Int: Int] = [:]
    let onInteraction: () -> Void

    @Environment(AppSettings.self) private var settings
    @Environment(\.scenePhase) private var scenePhase
    @State private var driver = ReaderScrollDriver()
    @State private var imagePreparation = ReaderImagePreparation()
    @State private var visiblePages: ClosedRange<Int>?
    @State private var retries: [Int: Int] = [:]
    @State private var failedPage: Int?
    @State private var viewport: CGSize = .zero
    @State private var pinch = StripPinch()
    /// The reader opened on its page (once the screen's size is known and the pages are laid out at their width).
    @State private var placed = false

    /// Most pages' shape (width / height), from what the gallery says.
    private var typicalRatio: CGFloat {
        let ratios = pages.prefix(12).map(\.aspectRatio).filter { $0.isFinite && $0 > 0 }.sorted()
        return ratios.isEmpty ? 0.7 : ratios[ratios.count / 2]
    }

    /// The default width: a whole page on screen (the screen's width at most).
    private var baseWidth: CGFloat { ContinuousSizing.baseWidth(viewport: viewport, ratio: typicalRatio) }

    private func stripWidth(_ scale: CGFloat) -> CGFloat {
        let base = baseWidth
        return base * ContinuousSizing.clamp(scale, base: base)
    }

    /// Where a strip narrower than the screen starts: it stays in the middle
    /// (only a strip wider than the screen moves sideways).
    private func sides(_ scale: CGFloat) -> CGFloat { max(0, (viewport.width - stripWidth(scale)) / 2) }

    var body: some View {
        ScrollViewReader { proxy in
            // Both ways: a strip narrower or wider than the screen moves sideways too, with the system's inertia.
            ScrollView([.vertical, .horizontal]) {
                LazyVStack(spacing: settings.readMode == .verticalGap ? settings.pageGap : 0) {
                    ForEach(pages) { item in
                        ContinuousReaderPage(item: item, retry: retries[item.index, default: 0] + reloads[item.index, default: 0] * 1000,
                                             knownRatio: driver.knownRatios[item.index],
                                             enhancement: item.url.flatMap { imageOptions.enhancement(for: $0, continuous: true) },
                                             enhanceNow: scenePhase == .active && visiblePages?.lowerBound == item.index,
                                             onReload: {
                                                 if let url = item.url { ImagePipeline.shared.cache.removeCachedData(for: ImageRequest(url: url)) }
                                                 retries[item.index, default: 0] += 1
                                             }) { state in
                            switch state {
                            case .loading: driver.image(index: item.index)
                            case .failed: driver.image(index: item.index, failed: true)
                            case .ready(let size):
                                driver.image(index: item.index, size: size)
                                if failedPage == item.index { failedPage = nil }
                            }
                        }
                        .background(ReaderPageAnchor(index: item.index, driver: driver).allowsHitTesting(false))
                        .id(item.index)
                    }
                }
                // Not laid out before the screen's size is known (scrolled both ways, nothing bounds the width).
                .frame(width: viewport.width > 0 ? stripWidth(scale) : 1)
                // Narrower than the screen: in the middle, not moving sideways.
                .frame(minWidth: viewport.width)
                .opacity(placed ? 1 : 0)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .paneScrollEdges()
            .onScrollGeometryChange(for: CGRect.self) {
                CGRect(origin: $0.contentOffset, size: $0.containerSize)
            } action: { _, new in
                driver.viewportChanged(new.size)
                #if DEBUG
                if let scroll = driver.scroll { ReaderDebugMetrics.shared.record(scroll) }
                #endif
            }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                let first = viewport.width == 0
                let widthChanged = abs(viewport.width - size.width) > 1
                viewport = size
                guard widthChanged else { return }
                // First laid out: the page that was opened (not the first one, which
                // shows while the pages have no width yet). Turned: the page being read.
                let target = first ? request.page : page
                DispatchQueue.main.async {
                    proxy.scrollTo(target, anchor: .top)
                    DispatchQueue.main.async {
                        proxy.scrollTo(target, anchor: .top)
                        placed = true
                        driver.reportPage()
                    }
                }
            }
            .onChange(of: stripWidth(scale)) { updateMovable() }
            .onChange(of: viewport) { updateMovable() }
            .onScrollPhaseChange { _, phase in
                if phase == .interacting {
                    driver.userInteractionStarted()
                    onInteraction()
                }
            }
            .onAppear {
                configure()
                proxy.scrollTo(request.page, anchor: .top)
            }
            // The two-finger pinch goes on the strip's own scroll view (once
            // its pages are laid out), like a page's: it isn't the reader's close.
            .task {
                for _ in 0..<40 where !pinch.isAttached {
                    if let scroll = driver.scroll {
                        // Enlarged, the strip moves every way the finger does (no locking to one direction).
                        scroll.isDirectionalLockEnabled = false
                        pinch.attach(to: scroll)
                        ReaderGestureLock.enlargedNow = { [weak scroll] in
                            guard let scroll else { return false }
                            return scroll.contentSize.width > scroll.bounds.width + 1
                        }
                    } else {
                        try? await Task.sleep(for: .milliseconds(50))
                    }
                }
            }
            .onChange(of: resetRequest) { resize(to: 1, anchor: nil) }
            .onChange(of: request) { _, new in
                driver.stop()
                running = false
                if new.screens != 0, let scroll = driver.scroll {
                    // A screen at a time, a little of the last one still showing.
                    let inset = scroll.adjustedContentInset
                    let height = scroll.bounds.height - inset.top - inset.bottom
                    let bottom = max(-inset.top, scroll.contentSize.height - scroll.bounds.height + inset.bottom)
                    let y = min(max(scroll.contentOffset.y + CGFloat(new.screens) * height * 0.9, -inset.top), bottom)
                    scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: y), animated: true)
                    return
                }
                if new.animated {
                    withAnimation(.smooth(duration: 0.3)) { proxy.scrollTo(new.page, anchor: .top) }
                } else {
                    proxy.scrollTo(new.page, anchor: .top)
                }
            }
            .onChange(of: settings.autoScrollSecondsPerScreen) { driver.secondsPerScreen = settings.autoScrollSecondsPerScreen }
            .onChange(of: settings.continueAutoScrollOnDrag) { driver.continueOnDrag = settings.continueAutoScrollOnDrag }
            .onChange(of: imageOptions) { configure(); prepare(visiblePages) }
            .onChange(of: scenePhase) { configure(); prepare(visiblePages) }
            .onChange(of: running) { _, active in
                guard active else { driver.stop(); return }
                guard !pages.isEmpty else { running = false; return }
                failedPage = nil
                configure()
                driver.start()
                // Further ahead while it scrolls on its own.
                prepare(visiblePages)
            }
            .overlay {
                if let failedPage {
                    VStack(spacing: 10) {
                        Text("Auto Scroll Paused").font(.subheadline)
                        Button("Retry") {
                            retries[failedPage, default: 0] += 1
                            driver.image(index: failedPage)
                            self.failedPage = nil
                        }
                        .buttonStyle(.borderedProminent)
                        .frame(minHeight: 44)
                    }
                    .padding(20)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
                }
            }
            .onDisappear { driver.disconnect(); imagePreparation.stop(); running = false }
        }
    }

    private func updateMovable() {
        // Only a strip wider than the screen moves sideways.
        let value = viewport.width > 0 && stripWidth(scale) > viewport.width + 1
        if movable != value { movable = value }
    }

    /// A new width (pinched, or Reset Size): the point under the fingers
    /// (or the middle of the screen) stays where it was. A picture of the
    /// screen covers the moment the pages are laid out again.
    private func resize(to new: CGFloat, anchor fingers: CGPoint?) {
        let old = scale
        guard let scroll = driver.scroll, viewport.width > 0, abs(new - old) > 0.001 else {
            UIView.animate(withDuration: 0.25) { driver.scroll?.transform = .identity }
            return
        }
        let anchor = fingers ?? CGPoint(x: viewport.width / 2, y: viewport.height / 2)
        let cover = scroll.window.flatMap { window -> UIView? in
            guard let picture = window.snapshotView(afterScreenUpdates: false) else { return nil }
            picture.frame = window.bounds
            picture.isUserInteractionEnabled = false
            window.addSubview(picture)
            return picture
        }
        scroll.transform = .identity
        let oldWidth = stripWidth(old), newWidth = stripWidth(new)
        let oldSides = sides(old), newSides = sides(new)
        let factor = newWidth / max(oldWidth, 1)
        let gap = settings.readMode == .verticalGap ? CGFloat(settings.pageGap) : 0
        let gapsAbove = CGFloat(page) * gap
        let offset = scroll.contentOffset
        let inset = scroll.adjustedContentInset
        // The point under the anchor, in the strip (from its left side and its top), before and after.
        let x = offset.x + anchor.x - oldSides
        let y = offset.y + anchor.y
        let newX = x * factor + newSides
        let newY = (y - gapsAbove) * factor + gapsAbove
        scale = new
        DispatchQueue.main.async {
            scroll.layoutIfNeeded()
            let maxX = max(-inset.left, scroll.contentSize.width - scroll.bounds.width + inset.right)
            let maxY = max(-inset.top, scroll.contentSize.height - scroll.bounds.height + inset.bottom)
            let target = CGPoint(x: min(max(-inset.left, newX - anchor.x), maxX),
                                 y: min(max(-inset.top, newY - anchor.y), maxY))
            scroll.setContentOffset(target, animated: false)
            driver.reportPage()
            guard let cover else { return }
            DispatchQueue.main.async {
                UIView.animate(withDuration: 0.12, animations: { cover.alpha = 0 }) { _ in cover.removeFromSuperview() }
            }
        }
    }

    private func configure() {
        driver.lastPage = pages.last?.index
        // Live values (a binding and state read when the pinch asks).
        pinch.currentScale = { scale }
        pinch.range = {
            let base = baseWidth
            return (ContinuousSizing.minimumScale(base: base), ContinuousSizing.maximumScale)
        }
        pinch.onEnd = { next in resize(to: next, anchor: pinch.takeAnchor()) }
        driver.secondsPerScreen = settings.autoScrollSecondsPerScreen
        driver.continueOnDrag = settings.continueAutoScrollOnDrag
        // Not before the reader is on its page (the first page shows while nothing has a width yet).
        driver.onPage = { index in if placed, page != index { page = index } }
        driver.onVisiblePages = { range in
            visiblePages = range
            prepare(range)
        }
        driver.onStop = { reason in
            if case .failed(let index) = reason { failedPage = index }
            running = false
        }
    }

    private func prepare(_ range: ClosedRange<Int>?) {
        guard scenePhase == .active, let range else { imagePreparation.stop(); return }
        let ahead = ReaderImagePreparation.ahead(autoScrolling: running, secondsPerScreen: settings.autoScrollSecondsPerScreen)
        let urls = pages.dropFirst(range.upperBound + 1).prefix(ahead).compactMap(\.url)
        imagePreparation.prepare(urls.map { url in
            .init(request: ReaderStripImages.request(for: url, priority: .low),
                  enhancement: imageOptions.enhancement(for: url, continuous: true))
        })
    }
}

private struct ContinuousReaderPage: View {
    let item: ReaderPage
    let retry: Int
    let knownRatio: CGFloat?
    let enhancement: ReaderImageEnhancement?
    let enhanceNow: Bool
    /// Reload Page, from the button under a page still loading after a while.
    let onReload: () -> Void
    let onLoad: (RemoteImageLoadState) -> Void
    @State private var loadedRatio: CGFloat?
    @State private var loading = true
    @State private var slow = false

    private var ratio: CGFloat {
        let value = loadedRatio ?? knownRatio ?? item.url.flatMap {
            ReaderStripImages.cachedAspectRatio(for: ReaderStripImages.request(for: $0))
        } ?? item.aspectRatio
        return value.isFinite && value > 0 ? value : 0.7
    }

    var body: some View {
        Color.clear
            .aspectRatio(ratio, contentMode: .fit)
            .overlay {
                RemoteImage(url: item.url, contentMode: .fit, targetWidth: nil, retryOnFailure: true,
                            makeRequest: { ReaderStripImages.request(for: $0) },
                            enhancement: enhancement, enhanceNow: enhanceNow,
                            // Until it's loaded: its thumbnail, from memory.
                            placeholder: item.url.flatMap { ReaderPlaceholders.image(for: $0) }) { state in
                    if case .ready(let size) = state, size.width > 0, size.height > 0 { loadedRatio = size.width / size.height }
                    if case .loading = state { loading = true } else { loading = false }
                    onLoad(state)
                }
                .id(retry)
            }
            .overlay {
                // Still not there after a while: Reload Page, where the page is.
                if loading, slow {
                    Button("Reload Page", systemImage: "arrow.clockwise", action: onReload)
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .tint(.white)
                        .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.25), value: slow && loading)
            .task(id: retry) {
                slow = false
                loading = true
                try? await Task.sleep(for: .seconds(10))
                if !Task.isCancelled { slow = true }
            }
            .onAppear { if item.url == nil { onLoad(.failed) } }
    }
}

/// The strip's two-finger pinch, on its scroll view: while the fingers move
/// the strip is scaled as a picture around them (smooth); when they lift,
/// [onEnd] gets the new width and the pages are laid out at it.
@MainActor
final class StripPinch: NSObject, UIGestureRecognizerDelegate {
    var currentScale: () -> CGFloat = { 1 }
    /// The smallest and largest scale.
    var range: () -> (CGFloat, CGFloat) = { (1, 1) }
    var onEnd: (CGFloat) -> Void = { _ in }
    private weak var scroll: UIScrollView?
    private var start: CGFloat = 1
    private var anchor: CGPoint?
    private var pinching: CGPoint?

    var isAttached: Bool { scroll != nil }

    func attach(to scroll: UIScrollView) {
        guard self.scroll !== scroll else { return }
        self.scroll = scroll
        let recognizer = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        recognizer.delegate = self
        scroll.addGestureRecognizer(recognizer)
    }

    /// Where the fingers were (on screen, in the strip's view), for the resize that follows; once.
    func takeAnchor() -> CGPoint? {
        defer { anchor = nil }
        return anchor
    }

    @objc private func pinched(_ recognizer: UIPinchGestureRecognizer) {
        guard let scroll else { return }
        switch recognizer.state {
        case .began:
            start = currentScale()
            let point = recognizer.location(in: scroll)
            pinching = CGPoint(x: point.x - scroll.bounds.minX, y: point.y - scroll.bounds.minY)
        case .changed:
            guard let pinching else { return }
            let (low, high) = range()
            // Past the limits it gives way less and less (it settles back on lifting).
            let wanted = start * recognizer.scale
            let shown: CGFloat
            if wanted < low {
                shown = low * pow(wanted / low, 0.3)
            } else if wanted > high {
                shown = high * pow(wanted / high, 0.3)
            } else {
                shown = wanted
            }
            let factor = shown / max(start, 0.001)
            let dx = pinching.x - scroll.bounds.width / 2
            let dy = pinching.y - scroll.bounds.height / 2
            scroll.transform = CGAffineTransform(translationX: dx, y: dy).scaledBy(x: factor, y: factor).translatedBy(x: -dx, y: -dy)
        case .ended, .cancelled, .failed:
            let (low, high) = range()
            var next = min(max(start * recognizer.scale, low), high)
            // Almost the usual size: the usual size.
            if abs(next - 1) < 0.04 { next = 1 }
            if abs(next - start) < 0.001 {
                UIView.animate(withDuration: 0.25) { scroll.transform = .identity }
            } else {
                anchor = pinching
                onEnd(next)
            }
            pinching = nil
        default:
            break
        }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
