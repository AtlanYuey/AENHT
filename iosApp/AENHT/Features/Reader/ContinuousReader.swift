import SwiftUI

/// A command, separate from observed reading progress. Only an explicit jump scrolls to an ID.
struct ReaderScrollRequest: Equatable {
    let page: Int
    var animated = false
    private let id = UUID()
    init(page: Int, animated: Bool = false) { self.page = page; self.animated = animated }
}

struct ContinuousReader: View {
    let pages: [ReaderPage]
    @Binding var page: Int
    let request: ReaderScrollRequest
    @Binding var running: Bool
    let onInteraction: () -> Void

    @Environment(AppSettings.self) private var settings
    @State private var driver = ReaderScrollDriver()
    @State private var retries: [Int: Int] = [:]
    @State private var failedPage: Int?

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                LazyVStack(spacing: settings.readMode == .verticalGap ? settings.pageGap : 0) {
                    ForEach(pages) { item in
                        ContinuousReaderPage(item: item, retry: retries[item.index, default: 0], knownRatio: driver.knownRatios[item.index]) { state in
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
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGRect.self) {
                CGRect(origin: $0.contentOffset, size: $0.containerSize)
            } action: { _, new in
                driver.viewportChanged(new.size)
            }
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
            .onChange(of: request) { _, new in
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
            .onChange(of: running) { _, active in
                guard active else { driver.stop(); return }
                guard !pages.isEmpty else { running = false; return }
                failedPage = nil
                configure()
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
                        .frame(minHeight: 44)
                    }
                    .padding(20)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
                }
            }
            .onDisappear { driver.disconnect(); running = false }
        }
    }

    private func configure() {
        driver.lastPage = pages.last?.index
        driver.secondsPerScreen = settings.autoScrollSecondsPerScreen
        driver.continueOnDrag = settings.continueAutoScrollOnDrag
        driver.onPage = { index in if page != index { page = index } }
        driver.onStop = { reason in
            if case .failed(let index) = reason { failedPage = index }
            running = false
        }
    }
}

private struct ContinuousReaderPage: View {
    let item: ReaderPage
    let retry: Int
    let knownRatio: CGFloat?
    let onLoad: (RemoteImageLoadState) -> Void
    @State private var loadedRatio: CGFloat?

    private var ratio: CGFloat {
        let value = loadedRatio ?? knownRatio ?? item.aspectRatio
        return value.isFinite && value > 0 ? value : 0.7
    }

    var body: some View {
        Color.clear
            .aspectRatio(ratio, contentMode: .fit)
            .overlay {
                RemoteImage(url: item.url, contentMode: .fit, targetWidth: nil, retryOnFailure: true,
                            thumbnailSize: CGSize(width: 1400, height: 8192)) { state in
                    if case .ready(let size) = state, size.width > 0, size.height > 0 { loadedRatio = size.width / size.height }
                    onLoad(state)
                }
                .id(retry)
            }
            .onAppear { if item.url == nil { onLoad(.failed) } }
    }
}
