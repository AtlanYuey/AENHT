import SwiftUI
import UIKit

/// A command, separate from observed reading progress. Only an explicit jump scrolls to an ID.
struct ReaderScrollRequest: Equatable {
    let page: Int
    var animated = false
    private let id = UUID()
    init(page: Int, animated: Bool = false) { self.page = page; self.animated = animated }
}

struct ContinuousReader: View {
    let pages: [ReaderPage]
    let imageOptions: ReaderImageOptions
    @Binding var page: Int
    let request: ReaderScrollRequest
    @Binding var running: Bool
    let resetSizeRequest: Int
    let onSizeChanged: (ReaderSizeStatus) -> Void
    let onInteraction: () -> Void

    @Environment(AppSettings.self) private var settings
    @Environment(\.scenePhase) private var scenePhase
    @State private var driver = ReaderScrollDriver()
    @State private var imagePreparation = ReaderImagePreparation()
    @State private var visiblePages: ClosedRange<Int>?
    @State private var retries: [Int: Int] = [:]
    @State private var failedPage: Int?
    @State private var scale: CGFloat
    @State private var settledScale: CGFloat
    @State private var horizontalOffset: CGFloat?
    @State private var pinchStart: PinchStart?

    private struct PinchStart {
        let scale: CGFloat
        let anchor: ReaderScrollDriver.ResizeAnchor?
    }

    init(pages: [ReaderPage], imageOptions: ReaderImageOptions, page: Binding<Int>,
         request: ReaderScrollRequest, running: Binding<Bool>, initialScale: CGFloat,
         resetSizeRequest: Int, onSizeChanged: @escaping (ReaderSizeStatus) -> Void,
         onInteraction: @escaping () -> Void) {
        self.pages = pages
        self.imageOptions = imageOptions
        self._page = page
        self.request = request
        self._running = running
        self.resetSizeRequest = resetSizeRequest
        self.onSizeChanged = onSizeChanged
        self.onInteraction = onInteraction
        _scale = State(initialValue: initialScale)
        _settledScale = State(initialValue: initialScale)
    }

    var body: some View {
        GeometryReader { geometry in
            strip(in: geometry.size)
        }
    }

    private func pageList(in viewport: CGSize) -> some View {
        let canvasWidth = ReaderPageSizing.stripCanvasWidth(viewportWidth: viewport.width, scale: scale)
        let scrollX = horizontalOffset ?? (canvasWidth - viewport.width) / 2
        return LazyVStack(spacing: settings.readMode == .verticalGap ? settings.pageGap : 0) {
            ForEach(pages) { item in
                ContinuousReaderPage(item: item, retry: retries[item.index, default: 0],
                                     knownRatio: driver.knownRatios[item.index],
                                     viewport: viewport, scale: scale, settledScale: settledScale,
                                     canvasWidth: canvasWidth, scrollX: scrollX, imageOptions: imageOptions,
                                     enhanceNow: scenePhase == .active && visiblePages?.lowerBound == item.index,
                                     driver: driver, onLoad: { updatePage(item.index, state: $0) })
                    .id(item.index)
            }
        }
    }

    private func updatePage(_ index: Int, state: RemoteImageLoadState) {
        switch state {
        case .loading: driver.image(index: index)
        case .failed: driver.image(index: index, failed: true)
        case .ready(let size):
            driver.image(index: index, size: size)
            if failedPage == index { failedPage = nil }
        }
    }

    private func strip(in viewport: CGSize) -> some View {
        ScrollViewReader { proxy in
            ScrollView([.vertical, .horizontal]) {
                pageList(in: viewport)
            }
            .defaultScrollAnchor(.top, for: .initialOffset)
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGRect.self) {
                CGRect(x: $0.contentOffset.x + $0.contentInsets.leading, y: $0.contentOffset.y,
                       width: $0.containerSize.width, height: $0.containerSize.height)
            } action: { _, new in
                if horizontalOffset != new.minX { horizontalOffset = new.minX }
                driver.viewportChanged(new.size)
            }
            .onScrollPhaseChange { _, phase in
                if phase == .interacting, pinchStart == nil {
                    driver.cancelResize()
                    driver.userInteractionStarted()
                    onInteraction()
                }
            }
            .onAppear {
                configure(viewport: viewport, proxy: proxy)
                onSizeChanged(ReaderSizeStatus(scale: scale))
                proxy.scrollTo(request.page, anchor: .top)
                driver.reportPage()
            }
            .onChange(of: request) { _, new in
                driver.cancelResize()
                driver.stop()
                running = false
                if new.animated {
                    withAnimation(.smooth(duration: 0.3)) { proxy.scrollTo(new.page, anchor: .top) }
                } else {
                    proxy.scrollTo(new.page, anchor: .top)
                }
            }
            .onChange(of: settings.autoScrollSecondsPerScreen) { driver.secondsPerScreen = settings.autoScrollSecondsPerScreen }
            .onChange(of: settings.continueAutoScrollOnDrag) { driver.continueOnDrag = settings.continueAutoScrollOnDrag }
            .onChange(of: settings.keepContinuousPageSize) {
                if settings.keepContinuousPageSize { settings.continuousPageScale = Double(settledScale) }
            }
            .onChange(of: resetSizeRequest) {
                resize(to: ReaderPageSizing.normalScale, in: viewport, anchor: driver.resizeAnchor(), recenter: true)
                finishSizing(in: viewport)
            }
            .onChange(of: viewport) { configure(viewport: viewport, proxy: proxy); prepare(visiblePages, in: viewport) }
            .onChange(of: imageOptions) { configure(viewport: viewport, proxy: proxy); prepare(visiblePages, in: viewport) }
            .onChange(of: scenePhase) {
                if scenePhase != .active, pinchStart != nil { finishSizing(in: viewport) }
                prepare(visiblePages, in: viewport)
            }
            .onChange(of: running) { _, active in
                guard active else { driver.stop(); return }
                guard !pages.isEmpty, !ReaderSizeStatus(scale: scale, adjusting: pinchStart != nil).blocksNavigation else {
                    running = false
                    return
                }
                failedPage = nil
                configure(viewport: viewport, proxy: proxy)
                driver.start()
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
                    }
                    .padding(14)
                    .modifier(GlassPanel(cornerRadius: 18))
                }
            }
            .onDisappear {
                if pinchStart != nil { finishSizing(in: viewport) }
                driver.disconnect()
                imagePreparation.stop()
            }
        }
    }

    private func configure(viewport: CGSize, proxy: ScrollViewProxy) {
        driver.lastPage = pages.last?.index
        driver.secondsPerScreen = settings.autoScrollSecondsPerScreen
        driver.continueOnDrag = settings.continueAutoScrollOnDrag
        driver.onPage = { index in if page != index { page = index } }
        driver.onVisiblePages = { range in
            visiblePages = range
            prepare(range, in: viewport)
        }
        driver.onStop = { reason in
            if case .failed(let index) = reason { failedPage = index }
            running = false
        }
        driver.onPinch = { gesture, y in
            switch gesture.state {
            case .began:
                driver.stop()
                running = false
                pinchStart = PinchStart(scale: scale, anchor: driver.resizeAnchor(at: y))
                onSizeChanged(ReaderSizeStatus(scale: scale, adjusting: true))
            case .changed:
                guard let start = pinchStart else { return }
                resize(to: start.scale * gesture.scale, in: viewport, anchor: start.anchor, viewportY: y)
            case .ended:
                if let start = pinchStart {
                    resize(to: start.scale * gesture.scale, in: viewport, anchor: start.anchor)
                    finishSizing(in: viewport)
                }
            case .cancelled, .failed:
                if pinchStart != nil { finishSizing(in: viewport) }
            default:
                break
            }
        }
        driver.onMissingResizeRow = { index in
            // SwiftUI may have recycled the anchor while shrinking the stack.
            if driver.restoringRow == index { proxy.scrollTo(index, anchor: .top) }
        }
    }

    private func resize(to proposed: CGFloat, in viewport: CGSize, anchor: ReaderScrollDriver.ResizeAnchor?,
                        viewportY: CGFloat? = nil, recenter: Bool = false) {
        let next = ReaderPageSizing.clamp(proposed)
        guard next != scale else { return }
        if let anchor {
            let width = ReaderPageSizing.width(in: viewport, aspectRatio: anchor.aspectRatio, scale: next)
            let canvasWidth = ReaderPageSizing.stripCanvasWidth(viewportWidth: viewport.width, scale: next)
            driver.preserve(anchor, width: width, viewportY: viewportY, canvasWidth: canvasWidth,
                            centerHorizontally: recenter)
        }
        scale = next
        onSizeChanged(ReaderSizeStatus(scale: next, adjusting: pinchStart != nil))
    }

    private func finishSizing(in viewport: CGSize) {
        pinchStart = nil
        settledScale = scale
        if settings.keepContinuousPageSize { settings.continuousPageScale = Double(scale) }
        onSizeChanged(ReaderSizeStatus(scale: scale))
        prepare(visiblePages, in: viewport)
    }

    private func prepare(_ range: ClosedRange<Int>?, in viewport: CGSize) {
        guard scenePhase == .active, let range else { imagePreparation.stop(); return }
        guard pinchStart == nil else { return }
        let next = pages.dropFirst(range.upperBound + 1).prefix(ReaderImagePreparation.turns)
        imagePreparation.prepare(next.compactMap { item in
            guard let url = item.url else { return nil }
            let request = ReaderStripImages.request(for: url, priority: .low)
            return .init(request: request, enhancement: imageOptions.enhancement(
                for: url, continuousViewport: viewport, continuousScale: settledScale
            ))
        })
    }
}

private struct ContinuousReaderPage: View {
    let item: ReaderPage
    let retry: Int
    let knownRatio: CGFloat?
    let viewport: CGSize
    let scale: CGFloat
    let settledScale: CGFloat
    let canvasWidth: CGFloat
    let scrollX: CGFloat
    let imageOptions: ReaderImageOptions
    let enhanceNow: Bool
    let driver: ReaderScrollDriver
    let onLoad: (RemoteImageLoadState) -> Void
    @State private var loadedRatio: CGFloat?

    private var ratio: CGFloat {
        let value = loadedRatio ?? knownRatio ?? item.url.flatMap {
            ReaderStripImages.cachedAspectRatio(for: ReaderStripImages.request(for: $0))
        } ?? item.aspectRatio
        return ReaderPageSizing.aspectRatio(value)
    }

    var body: some View {
        let aspect = ratio
        let width = ReaderPageSizing.width(in: viewport, aspectRatio: aspect, scale: scale)
        let offset = ReaderPageSizing.stripImageOffset(scrollX: scrollX, viewportWidth: viewport.width,
                                                       canvasWidth: canvasWidth, imageWidth: width)
        Color.clear
            .frame(width: width, height: width / aspect)
            .overlay {
                RemoteImage(url: item.url, contentMode: .fit, targetWidth: nil, retryOnFailure: true,
                            makeRequest: { ReaderStripImages.request(for: $0) },
                            enhancement: item.url.flatMap { imageOptions.enhancement(
                                for: $0, continuousViewport: viewport, continuousScale: settledScale
                            ) },
                            enhanceNow: enhanceNow) { state in
                    if case .ready(let size) = state, size.width > 0, size.height > 0 { loadedRatio = size.width / size.height }
                    onLoad(state)
                }
                .id(retry)
            }
            // The driver's ready check measures the image's width. The outer
            // frame covers the black margins as part of the same scroll view.
            .background(ReaderPageAnchor(index: item.index, driver: driver).allowsHitTesting(false))
            .offset(x: offset)
            .frame(width: canvasWidth)
            .clipped()
            .contentShape(Rectangle())
            .onAppear { if item.url == nil { onLoad(.failed) } }
    }
}
