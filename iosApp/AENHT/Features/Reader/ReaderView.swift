import Nuke
import Photos
import Shared
import SwiftData
import SwiftUI
import UIKit

// MARK: - Source

struct ReaderPage: Hashable, Identifiable {
    let index: Int
    let url: URL?
    let aspectRatio: CGFloat
    var id: Int { index }
}

/// What the reader shows: a gallery's pages (local files when downloaded).
struct ReaderSource: Hashable {
    let id: Int
    let title: String
    let pages: [ReaderPage]
    let shareURL: URL
    let item: GalleryItem
    let numPages: Int

    #if DEBUG
    init(testPages: [ReaderPage]) {
        id = 1
        title = "Reader gesture fixture"
        pages = testPages
        shareURL = URL(fileURLWithPath: "/reader-gesture-fixture")
        item = GalleryItem(id: 1, mediaID: "", title: title, thumbnail: nil, numPages: testPages.count)
        numPages = testPages.count
    }
    #endif

    @MainActor
    init(detail: GalleryDetail) {
        id = detail.id
        title = detail.displayTitle
        shareURL = detail.url
        item = detail.asItem
        numPages = detail.numPages
        let downloads = DownloadManager.shared
        pages = detail.pages.enumerated().map { offset, page in
            ReaderPage(index: offset,
                       url: downloads.localURL(gallery: detail.id, page: offset) ?? page.imageURL,
                       aspectRatio: page.aspectRatio)
        }
    }

    @MainActor
    init(record: DownloadRecord) {
        id = record.id
        title = record.title
        item = record.item
        shareURL = item.webURL
        numPages = record.totalPages
        let downloads = DownloadManager.shared
        let card = item
        pages = (0..<record.totalPages).map { offset in
            let page = record.pages.indices.contains(offset) ? record.pages[offset] : nil
            let ratio = page.map { $0.width > 0 && $0.height > 0 ? CGFloat($0.width) / CGFloat($0.height) : 0.7 } ?? 0.7
            let remote: URL? = record.siteKind == .ehentai
                ? EhPageDataLoader.url(gid: record.id, token: record.token ?? "", page: offset + 1)
                : page.flatMap { URL(string: $0.remote) }
            return ReaderPage(index: offset, url: downloads.localURL(item: card, page: offset) ?? remote, aspectRatio: ratio)
        }
    }

    /// An E-Hentai gallery: each page's image is looked up as the reader gets to it.
    @MainActor
    init(eh detail: EhGalleryDetail, card: GalleryItem) {
        id = Int(detail.gid)
        title = detail.title
        item = card
        shareURL = card.webURL
        numPages = Int(detail.pages)
        let downloads = DownloadManager.shared
        pages = (0..<Int(detail.pages)).map { offset in
            ReaderPage(index: offset,
                       url: downloads.localURL(item: card, page: offset)
                           ?? EhPageDataLoader.url(gid: Int(detail.gid), token: detail.token, page: offset + 1),
                       aspectRatio: 0.7)
        }
    }
}

/// Pushes the reader (so the back swipe works).
struct ReaderRoute: Hashable {
    let source: ReaderSource
    let page: Int

    var startPage: Int { min(max(0, page), max(0, source.pages.count - 1)) }
}

// MARK: - Reader

struct ReaderView: View {
    let source: ReaderSource
    private let initialPage: Int

    @Binding private var page: Int
    @State private var scrollRequest: ReaderScrollRequest
    @State private var chromeVisible = true
    @State private var autoRead = false
    @State private var showSettings = false
    @State private var sliderValue: Double
    @State private var sliderEditing = false
    /// Hides the controls a while after the last touch.
    @State private var hider = ChromeHider()
    @State private var awake = ScreenAwake()
    /// The page was changed with the controls, which then stay.
    @State private var turnFromPanel = false
    @State private var isLandscape = false
    @State private var viewportPixels: CGSize = .zero
    @State private var sizeStatus = ReaderSizeStatus()
    @State private var resetSizeRequest = 0
    @State private var returnTarget: ReaderThumbnailID?
    @Environment(\.privacyMode) private var privacy
    @State private var askPage = false
    @State private var pageText = ""
    @State private var notice: String?

    @Environment(AppSettings.self) private var settings
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @Environment(\.scenePhase) private var scenePhase

    @State private var diskPrefetcher = ImagePrefetcher(
        pipeline: .shared, destination: .diskCache, maxConcurrentRequestCount: 3
    )
    /// What the prefetcher is loading now.
    @State private var prefetched: Set<URL> = []

    init(route: ReaderRoute, page: Binding<Int>) {
        source = route.source
        let start = route.startPage
        initialPage = start
        _page = page
        _scrollRequest = State(initialValue: ReaderScrollRequest(page: start))
        _sliderValue = State(initialValue: Double(start))
    }

    private var pageCount: Int { source.pages.count }

    private var imageOptions: ReaderImageOptions {
        let model: SuperResolutionModel = settings.superResolutionModel == .high && SuperResolutionModels.shared.isReady
            ? .high : .standard
        return ReaderImageOptions(viewport: viewportPixels, displayScale: displayScale, crop: settings.autoCrop,
                                  mode: settings.superResolution, model: model)
    }

    /// Double page: pages side by side, or nil for one at a time.
    private var spreads: Spreads? {
        let mode = settings.readMode
        guard mode == .leftToRight || mode == .rightToLeft else { return nil }
        guard settings.doublePage == .always || (settings.doublePage == .landscape && isLandscape) else { return nil }
        let wide = Set(source.pages.enumerated().filter { $0.element.aspectRatio > 1.05 }.map { KotlinInt(int: Int32($0.offset)) })
        return Spreads(pageCount: Int32(pageCount), cover: settings.doublePageCover, widePages: wide,
                       singleStep: settings.doublePageSingleStep)
    }

    /// What the paged reader turns: spreads of one or two pages.
    private func units(_ spreads: Spreads?) -> [[ReaderPage]] {
        guard let spreads else { return source.pages.map { [$0] } }
        return (0..<Int(spreads.count)).map { slot in
            spreads.pages(slot: Int32(slot)).map { source.pages[Int($0.int32Value)] }
        }
    }

    /// The page one turn away (-1 past the ends).
    private func step(forward: Bool) -> Int {
        if settings.readMode.isPaged, let spreads {
            return Int(spreads.step(page: Int32(page), delta: forward ? 1 : -1))
        }
        return forward ? page + 1 : page - 1
    }
    private var isRTL: Bool { settings.readMode == .rightToLeft }

    var body: some View {
        readingInteractions
            .task(id: page) {
                // Saved once the page settles, not on every turn (saving
                // refreshes the history list and costs a frame).
                try? await Task.sleep(for: .milliseconds(700))
                guard !Task.isCancelled else { return }
                recordPage()
            }
            .onChange(of: settings.readMode) {
                autoRead = false
                scrollRequest = ReaderScrollRequest(page: page)
                prefetch(around: page)
            }
            .onChange(of: pagedReaderID) {
                if settings.readMode.isPaged { sizeStatus = ReaderSizeStatus() }
            }
            .task(id: autoRead) { await runAutoRead() }
            .onAppear { AppChrome.shared.readersOpen += 1 }
            .onDisappear { AppChrome.shared.readersOpen = max(0, AppChrome.shared.readersOpen - 1) }
            .onAppear {
                awake.touched()
                ReaderProgress.shared.begin(page, gallery: source.item.transitionKey)
                prefetch(around: page)
                recordPage()
                scheduleHide()
            }
            .onDisappear {
                autoRead = false
                hider.cancel()
                awake.stop()
                diskPrefetcher.stopPrefetching()
                recordPage()
            }
            .modifier(PageAlerts(askPage: $askPage, pageText: $pageText, notice: $notice, pageCount: pageCount) { target in
                turnFromPanel = true
                autoRead = false
                go(target)
                sliderValue = Double(target)
            })
            .sheet(isPresented: $showSettings) {
                ReaderSettingsSheet()
                    .presentationDetents([.medium, .large])
            }
    }

    // Keep SwiftUI type inference bounded without changing modifier order.
    private var readingInteractions: some View {
        readerCanvas
            .background(TouchObserver { touch in
                // No view state changes here: one while the Options menu is
                // open rebuilds the toolbar and closes the menu under the
                // finger, before its item acts.
                awake.touched()
                guard chromeVisible else { return }
                if touch.onOverlay || touch.location.y < touch.safeArea.top + 64 {
                    // A menu, a sheet, or the top bar (its menu may be
                    // opening): the controls stay until a touch elsewhere.
                    hider.cancel()
                } else {
                    scheduleHide()
                }
            })
            .onChange(of: chromeVisible) { scheduleHide() }
            .onChange(of: settings.screenOn) { awake.touched() }
            .onChange(of: sliderEditing) {
                if sliderEditing { autoRead = false }
                scheduleHide()
            }
            .onChange(of: showSettings) {
                if showSettings { autoRead = false }
                scheduleHide()
            }
            .onChange(of: askPage) { if askPage { autoRead = false } }
            .onChange(of: scenePhase) { if scenePhase != .active { autoRead = false } }
            .onChange(of: autoRead) { if autoRead { chromeVisible = false } }
            .onChange(of: page) { _, new in
                sliderValue = Double(new)
                prefetch(around: new)
                // Continuous scrolling hides controls on a real drag or the idle timer, not on a page boundary.
                if turnFromPanel {
                    turnFromPanel = false
                } else if !sliderEditing, settings.readMode.isPaged, !autoRead {
                    chromeVisible = false
                }
            }
    }

    private var readerCanvas: some View {
        content
            .background(ReaderBackGestures(
                allowsEdge: allowsEdgeBack,
                openingThumbnail: thumbnail(for: initialPage),
                returnTarget: returnTarget,
                back: leaveReader,
                dismiss: { dismiss() }
            ).frame(width: 0, height: 0))
            .blur(radius: privacy == .blurImages ? 40 : 0)
            .grayscale(settings.readerGrayscale ? 1 : 0)
            .modifier(InvertIf(enabled: settings.readerInvert))
            .overlay {
                // The reader's own brightness: a dark layer over the pages.
                Color.black.opacity(min(0.8, 1 - settings.readerBrightness)).allowsHitTesting(false)
            }
            .background(Color.black)
            .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { isLandscape = $0 }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                viewportPixels = CGSize(width: size.width * displayScale, height: size.height * displayScale)
            }
            .ignoresSafeArea()
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .barToolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: leaveReader) {
                        Image(systemName: "chevron.backward")
                    }
                    .accessibilityLabel("Exit Reader")
                    .accessibilityIdentifier("reader-back")
                }
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text(source.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Text("\(page + 1) / \(pageCount)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    .frame(maxWidth: 240)
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Picker("Reading Direction", selection: Binding(
                            get: { settings.readMode },
                            set: { settings.readMode = $0 }
                        )) {
                            ForEach(ReadMode.allCases) { mode in
                                Label(mode.title, systemImage: mode.systemImage).tag(mode)
                            }
                        }
                        Section {
                            Button("Go to Page…", systemImage: "arrow.right.to.line") {
                                pageText = "\(page + 1)"
                                askPage = true
                            }
                            .accessibilityIdentifier("reader-go-to-page")
                            .disabled(sizeStatus.blocksNavigation)
                            Button("Save Page", systemImage: "square.and.arrow.down") { Task { await savePage() } }
                            Button("Share Page", systemImage: "photo") { Task { await sharePage() } }
                        }
                        Button("Reader Settings", systemImage: "slider.horizontal.3") {
                            showSettings = true
                        }
                        ShareLink(item: source.shareURL) {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Label("Options", systemImage: "ellipsis")
                    }
                    .accessibilityIdentifier("reader-options")
                }
            }
            .toolbar(chromeVisible ? .visible : .hidden, for: .navigationBar)
            .toolbar(.hidden, for: .tabBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .overlay(alignment: .bottom) {
                if chromeVisible {
                    bottomPanel
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .statusBarHidden(!chromeVisible)
            .persistentSystemOverlays(chromeVisible ? .automatic : .hidden)
            .environment(\.colorScheme, .dark)
            .animation(.smooth(duration: 0.25), value: chromeVisible)
    }

    // MARK: Pages

    private var allowsEdgeBack: Bool {
        // A deliberate left-edge swipe in a spread is an exit. A swipe
        // within the artwork stays available for moving the enlarged spread.
        guard returnTarget == nil, !sizeStatus.adjusting else { return false }
        return spreads != nil || (!sizeStatus.enlarged && (settings.readMode.isPaged || !sizeStatus.adjusted))
    }

    private func thumbnail(for index: Int) -> ReaderThumbnailID {
        ReaderThumbnailID(gallery: source.item.transitionKey, page: index)
    }

    private func leaveReader() {
        guard returnTarget == nil else { return }
        let current = thumbnail(for: page)
        returnTarget = current
        autoRead = false
        ReaderProgress.shared.track(current.page, gallery: current.gallery)
    }

    private var pagedReaderID: String {
        "\(settings.readMode.rawValue)-\(spreads == nil ? 1 : 2)-\(settings.doublePageCover)-\(settings.doublePageSingleStep)"
    }

    @ViewBuilder
    private var content: some View {
        if settings.readMode.isPaged {
            let spreads = spreads
            PagedReader(units: units(spreads), mode: settings.readMode, imageOptions: imageOptions,
                        isActive: scenePhase == .active, page: Binding(
                get: { spreads.map { Int($0.slotOf(page: Int32(page))) } ?? page },
                set: { slot in
                    guard returnTarget == nil else { return }
                    let first = spreads.map { Int($0.firstPage(slot: Int32(slot))) } ?? slot
                    if (spreads.map { Int($0.slotOf(page: Int32(page))) } ?? page) != slot { page = first }
                }
            ), resetSizeRequest: resetSizeRequest, onSizeChanged: sizeChanged, onTap: { point in
                handleTap(at: point)
            }, onSwipe: { direction in
                handleSwipe(direction)
            }, onSwipeTurn: { forward in
                guard !sizeStatus.blocksNavigation else { return }
                chromeVisible = false
                go(step(forward: forward))
            })
            .id(pagedReaderID)
        } else {
            ContinuousReader(pages: source.pages, imageOptions: imageOptions,
                             page: Binding(get: { page }, set: { if returnTarget == nil { page = $0 } }),
                             request: scrollRequest, running: $autoRead,
                             initialScale: settings.keepContinuousPageSize ? CGFloat(settings.continuousPageScale) : 1,
                             resetSizeRequest: resetSizeRequest, onSizeChanged: sizeChanged) {
                chromeVisible = false
            }
            .contentShape(Rectangle())
            .onTapGesture { if !sizeStatus.adjusting { chromeVisible.toggle() } }
        }
    }

    private func sizeChanged(_ status: ReaderSizeStatus) {
        guard status != sizeStatus else { return }
        let wasAdjusting = sizeStatus.adjusting
        sizeStatus = status
        if status.blocksNavigation { autoRead = false }
        if status.adjusting {
            hider.cancel()
        } else if wasAdjusting || status.adjusted {
            chromeVisible = true
            scheduleHide()
        }
    }

    /// The controls hide on their own a while (Reading settings, 3 s at
    /// least) after they were last touched, unless the slider or the
    /// settings are in use.
    private func scheduleHide() {
        guard chromeVisible, !sliderEditing, !showSettings, !sizeStatus.adjusting else {
            hider.cancel()
            return
        }
        hider.schedule(after: settings.readerControlsDelay) {
            if !sliderEditing, !showSettings, !sizeStatus.adjusting { chromeVisible = false }
        }
    }

    /// A tap at [point] (fractions of the page's width and height).
    private func handleTap(at point: CGPoint) {
        guard !sizeStatus.adjusting else { return }
        guard !sizeStatus.blocksNavigation, let forward = ReaderTapZones.forward(at: point, rightToLeft: isRTL) else {
            chromeVisible.toggle()
            return
        }
        go(step(forward: forward))
    }

    /// Swipes do what the Controls settings say: up and down with the left
    /// and right tap zones, left and right with the top and bottom ones. A
    /// swipe along the way the pages move turns or scrolls them instead.
    private func handleSwipe(_ direction: UISwipeGestureRecognizer.Direction) {
        guard !sizeStatus.blocksNavigation else { return }
        let vertical = settings.tapZones == .leftRight
        let pagesMoveVertically = settings.readMode != .leftToRight && settings.readMode != .rightToLeft
        guard vertical != pagesMoveVertically else { return }
        let gesture: ReaderGesture
        if vertical, direction == .up {
            gesture = settings.swipeUp
        } else if vertical, direction == .down {
            gesture = settings.swipeDown
        } else if !vertical, direction == .left {
            gesture = settings.swipeLeft
        } else if !vertical, direction == .right {
            gesture = settings.swipeRight
        } else {
            return
        }
        switch gesture {
        case .none: break
        case .exit: leaveReader()
        case .controls: chromeVisible.toggle()
        case .goToPage:
            pageText = "\(page + 1)"
            askPage = true
        case .autoRead: autoRead.toggle()
        }
    }

    /// The page shown, as decoded (from the image cache when it's there).
    private func pageImage() async -> UIImage? {
        guard source.pages.indices.contains(page), let url = source.pages[page].url else { return nil }
        return try? await ImagePipeline.shared.image(for: AutoCropProcessor.request(url))
    }

    private func savePage() async {
        guard let image = await pageImage() else {
            notice = String(localized: "Couldn't Save")
            return
        }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            notice = String(localized: "Couldn't Save")
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }
            notice = String(localized: "Saved to Photos.")
        } catch {
            notice = String(localized: "Couldn't Save")
        }
    }

    private func sharePage() async {
        guard let image = await pageImage() else { return }
        let controller = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        controller.popoverPresentationController?.sourceView = top?.view
        controller.popoverPresentationController?.sourceRect = CGRect(x: (top?.view.bounds.midX ?? 0), y: 60, width: 1, height: 1)
        top?.present(controller, animated: true)
    }

    private func recordPage() {
        modelContext.recordHistory(item: source.item, title: source.title,
                                   numPages: source.numPages, page: page)
    }

    private func go(_ target: Int, automatic: Bool = false) {
        guard returnTarget == nil, !sizeStatus.blocksNavigation, (0..<pageCount).contains(target) else { return }
        if !automatic { autoRead = false }
        if settings.readMode.isPaged {
            page = target
        } else {
            scrollRequest = ReaderScrollRequest(page: target, animated: settings.animatePageTurns)
        }
    }

    /// A page turn from the controls, which stay on screen.
    private func goFromPanel(_ target: Int) {
        guard !sizeStatus.blocksNavigation, (0..<pageCount).contains(target), target != page else { return }
        turnFromPanel = true
        go(target)
    }

    /// Loads the pages around [index] ahead. Pages left behind by fast
    /// turning are dropped: they would hold up the one being read (E-Hentai
    /// answers a few page requests a second).
    private func prefetch(around index: Int) {
        let upper = min(pageCount, index + 1 + settings.preloadPages)
        let lower = max(0, index - 2)
        guard lower < upper else { return }
        let urls = source.pages[lower..<upper].compactMap(\.url).filter { !$0.isFileURL }
        // Keep the settings-controlled window on disk. The two upcoming turns
        // use their actual display requests in the reader's preparation queue.
        let stale = prefetched.subtracting(urls)
        if !stale.isEmpty { diskPrefetcher.stopPrefetching(with: Array(stale)) }
        prefetched = Set(urls)
        diskPrefetcher.startPrefetching(with: urls)
    }

    private func runAutoRead() async {
        guard autoRead, settings.readMode.isPaged, !sizeStatus.blocksNavigation else { return }
        chromeVisible = false
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(settings.autoReadInterval))
            guard !Task.isCancelled else { return }
            let next = step(forward: true)
            guard (0..<pageCount).contains(next) else {
                autoRead = false
                return
            }
            go(next, automatic: true)
        }
    }

    private var autoReadTitle: LocalizedStringKey {
        if settings.readMode.isPaged { return autoRead ? "Stop Auto Read" : "Auto Read" }
        return autoRead ? "Pause Auto Scroll" : "Auto Scroll"
    }

    // MARK: Bottom panel

    private var bottomPanel: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Text("\(page + 1)")
                    .font(.footnote.monospacedDigit())
                    .frame(minWidth: 28)
                Slider(
                    value: $sliderValue,
                    in: 0...Double(max(1, pageCount - 1)),
                    step: 1,
                    onEditingChanged: { editing in
                        sliderEditing = editing
                        if !editing { goFromPanel(Int(sliderValue)) }
                    }
                )
                .disabled(sizeStatus.blocksNavigation)
                .environment(\.layoutDirection, isRTL ? .rightToLeft : .leftToRight)
                Text("\(pageCount)")
                    .font(.footnote.monospacedDigit())
                    .frame(minWidth: 28)
            }
            // Each control is touched over at least 44 × 44 pt (the glyphs alone are
            // too small to hit on iPad), and a touch between them stays on the panel.
            HStack(spacing: 0) {
                Button {
                    goFromPanel(step(forward: isRTL))
                } label: {
                    Image(systemName: "chevron.left").panelTarget()
                }
                .disabled(sizeStatus.blocksNavigation)
                Spacer(minLength: 8)
                Button {
                    autoRead.toggle()
                } label: {
                    Label(autoReadTitle,
                          systemImage: autoRead ? "pause.fill" : "play.fill")
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .panelTarget()
                }
                .disabled(sizeStatus.blocksNavigation)
                if sizeStatus.adjusted {
                    Spacer(minLength: 4)
                    Button {
                        resetSizeRequest += 1
                    } label: {
                        Image(systemName: "arrow.counterclockwise").panelTarget()
                    }
                    .accessibilityLabel("Reset Page Size")
                }
                Spacer(minLength: 8)
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "slider.horizontal.3").panelTarget()
                }
                .accessibilityLabel("Reader Settings")
                Spacer(minLength: 8)
                Button {
                    goFromPanel(step(forward: !isRTL))
                } label: {
                    Image(systemName: "chevron.right").panelTarget()
                }
                .disabled(sizeStatus.blocksNavigation)
            }
            .buttonStyle(.borderless)
            .font(.body.weight(.medium))
            .labelStyle(.titleAndIcon)
        }
        .padding(.horizontal, 12)
        .padding(.top, 14)
        .padding(.bottom, 6)
        .modifier(GlassPanel())
        // The whole panel takes touches: one that misses a control doesn't
        // reach the page under it (which would hide the controls).
        .contentShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: 640)
    }
}

private extension View {
    /// A control of the reader's panel: at least 44 × 44 pt to touch, all of it.
    func panelTarget() -> some View {
        frame(minWidth: 44, minHeight: 44)
            .padding(.horizontal, 4)
            .contentShape(Rectangle())
    }
}

/// Runs [hide] a while after the last [schedule]. It holds no view state,
/// so a touch changes nothing on screen until the controls actually hide.
/// Keeps the screen on while reading, until the Keep Screen On time has
/// passed without a touch (Reading settings). It then only stops holding
/// the screen on: the system's auto-lock decides, never sooner than usual.
@MainActor
private final class ScreenAwake {
    private var timer: Task<Void, Never>?

    func touched() {
        timer?.cancel()
        let mode = AppSettings.shared.screenOn
        UIApplication.shared.isIdleTimerDisabled = mode != .off
        guard let minutes = mode.minutes else { return }
        timer = Task {
            try? await Task.sleep(for: .seconds(minutes * 60))
            guard !Task.isCancelled else { return }
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    func stop() {
        timer?.cancel()
        UIApplication.shared.isIdleTimerDisabled = false
    }
}

@MainActor
private final class ChromeHider {
    private var task: Task<Void, Never>?

    func schedule(after seconds: Double, _ hide: @escaping @MainActor () -> Void) {
        task?.cancel()
        task = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            hide()
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

/// A floating panel: Liquid Glass on iOS 26, material before.
struct GlassPanel: ViewModifier {
    var cornerRadius: CGFloat = 28

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }
}

struct ReaderSettingsSheet: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                ReadingSettingsSections(settings: settings)
            }
            .navigationTitle("Reader Settings")
            .navigationBarTitleDisplayMode(.inline)
            .barToolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", systemImage: "checkmark") { dismiss() }
                }
            }
        }
    }
}

/// Shared by the reader sheet and the Reading settings page.
struct ReadingSettingsSections: View {
    @Bindable var settings: AppSettings
    /// Reading, Display or Controls (three tabs, as in NextN).
    @State private var tab = 0

    var body: some View {
        Section {
            Picker("Settings", selection: $tab) {
                Text("Reading").tag(0)
                Text("Display").tag(1)
                Text("Controls").tag(2)
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
        switch tab {
        case 0: reading
        case 1: display
        default:
            if settings.readMode.isPaged {
                controls
            } else {
                Section {
                    Toggle("Keep Page Size", isOn: $settings.keepContinuousPageSize)
                } footer: {
                    Text("Remember the size you set with two fingers for continuous reading on this device.")
                }
            }
        }
    }

    @ViewBuilder private var reading: some View {
        Section {
            Picker("Reading Direction", selection: $settings.readMode) {
                ForEach(ReadMode.allCases) { mode in
                    Label(mode.title, systemImage: mode.systemImage).tag(mode)
                }
            }
            // How a swipe turns the pages (not the taps: those are in Controls).
            if settings.readMode.isPaged {
                Toggle("Fade on Swipe", isOn: $settings.swipeFades)
            }
            if settings.readMode == .leftToRight || settings.readMode == .rightToLeft {
                Picker("Double Page", selection: $settings.doublePage) {
                    ForEach(DoublePage.allCases) { Text($0.title).tag($0) }
                }
                if settings.doublePage != .off {
                    Toggle("Cover Page Alone", isOn: $settings.doublePageCover)
                    Toggle("Turn One Page at a Time", isOn: $settings.doublePageSingleStep)
                }
            }
            if settings.readMode.isPaged {
                Toggle("Auto Crop Margins", isOn: $settings.autoCrop)
            }
            if settings.readMode == .verticalGap {
                VStack(alignment: .leading) {
                    LabeledContent("Page Gap") {
                        Text("\(Int(settings.pageGap)) pt")
                            .monospacedDigit()
                    }
                    Slider(value: $settings.pageGap, in: 2...60, step: 2) {
                        Text("Page Gap")
                    }
                }
            }
        } footer: {
            if settings.readMode.isPaged {
                Text("With Fade on Swipe, the pages don't move with your finger: a swipe turns to the next or previous page, and the new one fades in, sweeping the way you swiped.")
            }
        }
        Section {
            if settings.readMode.isPaged {
                VStack(alignment: .leading) {
                    LabeledContent("Auto Read Interval") {
                        Text(settings.autoReadInterval, format: .number.precision(.fractionLength(1)))
                            + Text(" s")
                    }
                    Slider(value: $settings.autoReadInterval, in: 1...10, step: 0.5) {
                        Text("Auto Read Interval")
                    } minimumValueLabel: {
                        Image(systemName: "hare")
                    } maximumValueLabel: {
                        Image(systemName: "tortoise")
                    }
                }
            } else {
                VStack(alignment: .leading) {
                    if settings.autoScrollSecondsPerScreen <= 3 {
                        Text("Auto Scroll Speed")
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text("Speed may be too fast for comfortable reading")
                                .font(.caption).fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                            Text(settings.autoScrollSecondsPerScreen, format: .number.precision(.fractionLength(0...1)))
                                + Text(" s / screen")
                        }
                        .foregroundStyle(.red)
                    } else {
                        LabeledContent("Auto Scroll Speed") {
                            Text(settings.autoScrollSecondsPerScreen, format: .number.precision(.fractionLength(0...1)))
                                + Text(" s / screen")
                        }
                    }
                    // The right end is faster; elapsed time per screen is stored, not pixels per tick.
                    Slider(value: Binding(
                        get: { 31 - settings.autoScrollSecondsPerScreen },
                        set: { settings.autoScrollSecondsPerScreen = ReaderAutoScroll.shared.secondsPerScreen(value: 31 - $0) }
                    ), in: 1...30, step: 0.5) {
                        Text("Auto Scroll Speed")
                    } minimumValueLabel: {
                        Text("Slow")
                    } maximumValueLabel: {
                        Text("Fast")
                    }
                }
                Toggle("Continue Auto Scroll While Dragging", isOn: $settings.continueAutoScrollOnDrag)
            }
            Stepper(value: $settings.preloadPages, in: 1...10) {
                LabeledContent("Preload Pages", value: "\(settings.preloadPages)")
            }
        } footer: {
            Text("Pages ahead of the current one are downloaded in advance.")
        }
    }

    @ViewBuilder private var display: some View {
        Section {
            VStack(alignment: .leading) {
                HStack {
                    Text("Page Brightness")
                    Spacer()
                    Text("\(Int(settings.readerBrightness * 100))%").foregroundStyle(.secondary).monospacedDigit()
                }
                Slider(value: $settings.readerBrightness, in: 0.2...1, step: 0.05)
            }
            Toggle("Grayscale", isOn: $settings.readerGrayscale)
            Toggle("Invert Colors", isOn: $settings.readerInvert)
        } footer: {
            Text("Brightness, grayscale and inverted colors apply only to the pages.")
        }
        SuperResolutionSection(settings: settings)
        Section {
            Picker("Keep Screen On", selection: $settings.screenOn) {
                ForEach(ScreenOn.allCases) { Text($0.title).tag($0) }
            }
        } footer: {
            Text("The reader keeps the screen on this long after the last touch, then leaves it to the system's own auto-lock (it never turns the screen off sooner than the system would).")
        }
        Section {
            VStack(alignment: .leading) {
                LabeledContent("Hide Controls After") {
                    Text(settings.readerControlsDelay, format: .number.precision(.fractionLength(0)))
                        + Text(" s")
                }
                Slider(value: $settings.readerControlsDelay, in: 3...10, step: 1) {
                    Text("Hide Controls After")
                } minimumValueLabel: {
                    Text(verbatim: "3")
                } maximumValueLabel: {
                    Text(verbatim: "10")
                }
            }
        } footer: {
            Text("The reader's controls hide on their own after this long without a touch.")
        }
    }

    @ViewBuilder private var controls: some View {
        Section {
            Toggle("Tap to Turn Pages", isOn: $settings.tapToTurnPages)
            Picker("Tap Zones", selection: $settings.tapZones) {
                ForEach(TapZones.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Fade Page Turns", isOn: Binding(get: { !settings.animatePageTurns }, set: { settings.animatePageTurns = !$0 }))
        } footer: {
            Text("With fading page turns, pages don't slide: the next one fades in where you are, easier on the eyes when turning quickly.")
        }
        Section {
            // Across the tap zones: up and down with left and right zones.
            if settings.tapZones == .leftRight {
                gesturePicker("Swipe Down", $settings.swipeDown)
                gesturePicker("Swipe Up", $settings.swipeUp)
            } else {
                gesturePicker("Swipe Right", $settings.swipeRight)
                gesturePicker("Swipe Left", $settings.swipeLeft)
            }
        } header: {
            Text("Swipes")
        } footer: {
            Text("A long swipe. Not when it goes the way the pages turn or scroll (that turns them), nor while a page is zoomed in.")
        }
    }

    private func gesturePicker(_ title: LocalizedStringKey, _ value: Binding<ReaderGesture>) -> some View {
        Picker(title, selection: value) {
            ForEach(ReaderGesture.allCases) { Text($0.title).tag($0) }
        }
    }
}

// MARK: - Tap zones

/// Which taps turn the page, when enabled: the outer 30 % on the left and
/// right, or at the top and bottom (Reading › Tap Zones).
enum ReaderTapZones {
    static let edge: CGFloat = 0.3

    /// Whether a tap at [point] (fractions of the page) turns forward,
    /// back, or isn't a turn (nil).
    @MainActor
    static func forward(at point: CGPoint, rightToLeft: Bool) -> Bool? {
        let settings = AppSettings.shared
        guard settings.tapToTurnPages else { return nil }
        let forward: Bool
        switch settings.tapZones {
        case .leftRight:
            if point.x < edge { forward = rightToLeft } else if point.x > 1 - edge { forward = !rightToLeft } else { return nil }
        case .topBottom:
            if point.y < edge { forward = false } else if point.y > 1 - edge { forward = true } else { return nil }
        }
        return forward
    }
}

/// What counts as a reader swipe: long and straight, so a small slip
/// doesn't close the reader.
enum ReaderSwipe {
    static func direction(_ t: CGPoint, in size: CGSize) -> UISwipeGestureRecognizer.Direction? {
        let ax = abs(t.x)
        let ay = abs(t.y)
        if ay >= max(150, size.height * 0.2), ay >= 2.5 * ax { return t.y > 0 ? .down : .up }
        if ax >= max(150, size.width * 0.35), ax >= 2.5 * ay { return t.x > 0 ? .right : .left }
        return nil
    }
}

/// Lets the reader's swipes work alongside the page's own gestures, and
/// not on a zoomed-in page.
@MainActor
final class SwipeDelegate: NSObject, UIGestureRecognizerDelegate {
    var isBlocked: @MainActor () -> Bool = { false }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        !isBlocked()
    }
}

// MARK: - Paged reader

/// How the paged reader shows another page.
enum PageTurn: Equatable {
    /// The old page slides away as the new one slides in.
    case slide
    /// The pages change in place, the old one fading out: no motion.
    case fade
    /// The page shows at once.
    case instant
    /// The old page fades away from one side to the other with a soft edge,
    /// the way a swipe went (left or up: [toStart]).
    case sweep(vertical: Bool, toStart: Bool)
}

/// One page or spread at a time, left-to-right, right-to-left or top-to-bottom.
/// The active artwork owns its gestures. There is no scroll view around it.
struct PagedReader: UIViewControllerRepresentable {
    /// One or two pages (double page) per turn.
    let units: [[ReaderPage]]
    let mode: ReadMode
    let imageOptions: ReaderImageOptions
    let isActive: Bool
    @Binding var page: Int
    let resetSizeRequest: Int
    let onSizeChanged: (ReaderSizeStatus) -> Void
    let onTap: (CGPoint) -> Void
    let onSwipe: (UISwipeGestureRecognizer.Direction) -> Void
    /// A swipe in the reading direction turns by one unit.
    var onSwipeTurn: (Bool) -> Void = { _ in }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> ReaderPageHost {
        let controller = ReaderPageHost()
        context.coordinator.host = controller
        if let first = context.coordinator.controller(for: page) {
            controller.show(first)
        }
        context.coordinator.prepare(after: page)
        return controller
    }

    func updateUIViewController(_ controller: ReaderPageHost, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        let visible = controller.current
        visible?.configure(imageOptions, active: isActive)
        coordinator.resetSizeIfNeeded(for: visible)
        defer { coordinator.prepare(after: page) }
        let swipe = coordinator.pendingTurn
        coordinator.pendingTurn = nil
        guard let current = visible?.index, current != page else { return }
        // Swipes keep their own animation. Jumps show the page at once.
        let turn: PageTurn = abs(page - current) != 1 ? .instant
            : swipe ?? (AppSettings.shared.animatePageTurns ? .slide : .fade)
        coordinator.show(page, from: current, in: controller, turn: turn)
    }

    static func dismantleUIViewController(_ controller: ReaderPageHost, coordinator: Coordinator) {
        coordinator.stopPreparation()
    }

    @MainActor
    final class Coordinator {
        var parent: PagedReader
        weak var host: ReaderPageHost?
        private var running: (id: Int, cover: UIView, page: UIView)?
        private var turnID = 0
        private var cache: [Int: PageController] = [:]
        private let imagePreparation = ReaderImagePreparation()
        private var lastResetSizeRequest: Int
        var pendingTurn: PageTurn?

        init(_ parent: PagedReader) {
            self.parent = parent
            self.lastResetSizeRequest = parent.resetSizeRequest
        }

        func resetSizeIfNeeded(for visible: PageController?) {
            guard lastResetSizeRequest != parent.resetSizeRequest else { return }
            lastResetSizeRequest = parent.resetSizeRequest
            // UIKit reports its zoom synchronously. Deliver that after the SwiftUI update.
            DispatchQueue.main.async { [weak visible] in visible?.resetZoom() }
        }

        private func sizeChanged(_ status: ReaderSizeStatus, at index: Int) {
            guard host?.current?.index == index else { return }
            parent.onSizeChanged(status)
        }

        func prepare(after index: Int) {
            guard parent.isActive else { imagePreparation.stop(); return }
            let options = parent.imageOptions
            let upcoming = parent.units.dropFirst(index + 1).prefix(ReaderImagePreparation.turns)
            let items = upcoming.flatMap { unit in
                unit.compactMap { page -> ReaderImagePreparation.Item? in
                    guard let url = page.url else { return nil }
                    return .init(request: AutoCropProcessor.request(url, crop: options.crop, priority: .low),
                                 enhancement: options.enhancement(for: url, pagesPerTurn: unit.count))
                }
            }
            imagePreparation.prepare(items)
        }

        func stopPreparation() { imagePreparation.stop() }

        /// Left or up advances, except in right-to-left reading.
        func swipeTurn(_ t: CGPoint) -> Bool {
            guard host?.current?.sizeStatus.blocksNavigation == false else { return false }
            let vertical = parent.mode == .verticalPaged
            let along = vertical ? t.y : t.x
            let across = vertical ? t.x : t.y
            guard abs(along) >= 40, abs(along) >= 1.5 * abs(across) else { return false }
            let forward = parent.mode == .rightToLeft ? along > 0 : along < 0
            let next = parent.page + (forward ? 1 : -1)
            guard parent.units.indices.contains(next) else { return true }
            pendingTurn = AppSettings.shared.swipeFades ? .sweep(vertical: vertical, toStart: along < 0) : .slide
            parent.onSwipeTurn(forward)
            return true
        }

        func controller(for index: Int) -> PageController? {
            guard parent.units.indices.contains(index) else { return nil }
            if let cached = cache[index] {
                cached.configure(parent.imageOptions, active: parent.isActive)
                return cached
            }
            let controller = PageController(index: index, urls: parent.units[index].map(\.url),
                                            rightToLeft: parent.mode == .rightToLeft,
                                            options: parent.imageOptions, active: parent.isActive)
            controller.onTap = { [weak self] point in self?.parent.onTap(point) }
            controller.onSwipe = { [weak self] direction in self?.parent.onSwipe(direction) }
            controller.onSwipeTurn = { [weak self] t in self?.swipeTurn(t) ?? false }
            controller.onSizeChanged = { [weak self] status in self?.sizeChanged(status, at: index) }
            cache[index] = controller
            let current = parent.page
            cache = cache.filter { (current - 1...current + ReaderImagePreparation.turns).contains($0.key) || $0.key == index }
            return controller
        }

        /// Shows [index]. The container switches to it at once and
        /// the turn is drawn over it: the old page (a snapshot) slides away
        /// as the new one slides in, or fades out over it. A turn asked for
        /// while another runs ends that one at once and starts its own, so
        /// fast taps keep turning a page each instead of waiting and jumping.
        func show(_ index: Int, from current: Int, in host: ReaderPageHost, turn style: PageTurn) {
            guard let target = controller(for: index) else { return }
            let entersFromEnd = (index > current) != (parent.mode == .rightToLeft)
            endTurn()
            let container = host.view!
            var cover: UIView?
            if style != .instant, let old = host.current?.view,
               let snapshot = old.snapshotView(afterScreenUpdates: false) {
                snapshot.frame = old.convert(old.bounds, to: container)
                // Taps go through to the new page (fast turns).
                snapshot.isUserInteractionEnabled = false
                cover = snapshot
            }
            host.show(target)
            guard let cover, let page = target.view else { return }
            container.addSubview(cover)
            turnID += 1
            let id = turnID
            running = (id, cover, page)
            if style == .fade {
                UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseInOut, .allowUserInteraction]) {
                    cover.alpha = 0
                } completion: { [weak self] _ in
                    self?.endTurn(id)
                }
                return
            }
            if case let .sweep(vertical, toStart) = style {
                sweep(cover, vertical: vertical, toStart: toStart) { [weak self] in self?.endTurn(id) }
                return
            }
            // The new page comes in from the side the turn goes to.
            let vertical = parent.mode == .verticalPaged
            let distance = (vertical ? container.bounds.height : container.bounds.width) + 16
            let offset = entersFromEnd ? distance : -distance
            let move = { (amount: CGFloat) in
                vertical ? CGAffineTransform(translationX: 0, y: amount) : CGAffineTransform(translationX: amount, y: 0)
            }
            page.transform = move(offset)
            UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                page.transform = .identity
                cover.transform = move(-offset)
            } completion: { [weak self] _ in
                self?.endTurn(id)
            }
        }

        /// The old page ([cover]) goes from the side the finger started on to
        /// the side it went to, with a soft edge: a mask, opaque where the old
        /// page still shows, moved across it.
        private func sweep(_ cover: UIView, vertical: Bool, toStart: Bool, done: @escaping () -> Void) {
            let size = cover.bounds.size
            let length = vertical ? size.height : size.width
            let soft = length * 0.22
            let mask = CAGradientLayer()
            // Opaque over the page, fading out over [soft] past the side the finger went from.
            let span = length + soft
            mask.frame = vertical
                ? CGRect(x: 0, y: toStart ? 0 : -soft, width: size.width, height: span)
                : CGRect(x: toStart ? 0 : -soft, y: 0, width: span, height: size.height)
            let opaque = UIColor.black.cgColor
            let clear = UIColor.clear.cgColor
            let edge = NSNumber(value: Double(length / span))
            let start = NSNumber(value: Double(soft / span))
            mask.colors = toStart ? [opaque, opaque, clear] : [clear, opaque, opaque]
            mask.locations = toStart ? [0, edge, 1] : [0, start, 1]
            mask.startPoint = vertical ? CGPoint(x: 0.5, y: 0) : CGPoint(x: 0, y: 0.5)
            mask.endPoint = vertical ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 1, y: 0.5)
            cover.layer.mask = mask
            // Moved the way the finger went, until nothing of the old page is left.
            let distance = toStart ? -span : span
            let from = mask.position
            let to = vertical ? CGPoint(x: from.x, y: from.y + distance) : CGPoint(x: from.x + distance, y: from.y)
            CATransaction.begin()
            CATransaction.setCompletionBlock(done)
            let move = CABasicAnimation(keyPath: "position")
            move.fromValue = NSValue(cgPoint: from)
            move.toValue = NSValue(cgPoint: to)
            move.duration = 0.32
            move.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            mask.position = to
            mask.add(move, forKey: "sweep")
            CATransaction.commit()
        }

        /// Ends the running turn at once (only turn [id], when given).
        private func endTurn(_ id: Int? = nil) {
            guard let turn = running, id == nil || id == turn.id else { return }
            running = nil
            turn.cover.layer.removeAllAnimations()
            turn.cover.removeFromSuperview()
            turn.page.layer.removeAllAnimations()
            turn.page.transform = .identity
        }

    }
}

final class ReaderPageHost: UIViewController {
    private(set) var current: PageController?

    override func loadView() {
        let canvas = UIView()
        canvas.backgroundColor = .black
        canvas.clipsToBounds = true
        view = canvas
    }

    func show(_ next: PageController) {
        let previous = current
        guard previous !== next else { return }
        previous?.willMove(toParent: nil)
        addChild(next)
        current = next
        let displayed = view.window != nil
        if displayed {
            previous?.beginAppearanceTransition(false, animated: false)
            next.beginAppearanceTransition(true, animated: false)
        }
        next.view.frame = view.bounds
        next.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(next.view)
        previous?.view.removeFromSuperview()
        if displayed {
            previous?.endAppearanceTransition()
            next.endAppearanceTransition()
        }
        previous?.removeFromParent()
        next.didMove(toParent: self)
    }
}

final class PageController: UIViewController {
    let index: Int
    /// One page, or two shown side by side.
    let urls: [URL?]
    let rightToLeft: Bool
    var onTap: ((CGPoint) -> Void)?
    var onSwipe: ((UISwipeGestureRecognizer.Direction) -> Void)?
    /// The movement of a page swipe when the finger lifts.
    var onSwipeTurn: ((CGPoint) -> Bool)?
    var onSizeChanged: ((ReaderSizeStatus) -> Void)?
    private let zoomView = ZoomableImageView()
    private var options: ReaderImageOptions
    private var active: Bool
    private var visible = false
    var sizeStatus: ReaderSizeStatus { zoomView.sizeStatus }

    func resetZoom() { zoomView.resetZoom() }

    init(index: Int, urls: [URL?], rightToLeft: Bool, options: ReaderImageOptions, active: Bool) {
        self.index = index
        self.urls = urls
        self.rightToLeft = rightToLeft
        self.options = options
        self.active = active
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        view = zoomView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        #if DEBUG
        zoomView.testPage = index
        #endif
        zoomView.onTap = { [weak self] point in self?.onTap?(point) }
        zoomView.onSwipe = { [weak self] direction in self?.onSwipe?(direction) }
        zoomView.onSwipeTurn = { [weak self] t in self?.onSwipeTurn?(t) ?? false }
        zoomView.onSizeChanged = { [weak self] status in self?.onSizeChanged?(status) }
        zoomView.load(urls, rightToLeft: rightToLeft, options: options)
    }

    func configure(_ options: ReaderImageOptions, active: Bool) {
        let cropChanged = self.options.crop != options.crop
        self.options = options
        self.active = active
        guard isViewLoaded else { return }
        if cropChanged { zoomView.load(urls, rightToLeft: rightToLeft, options: options) }
        zoomView.updatePresentation(options, enhanceNow: visible && active)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        visible = true
        zoomView.updatePresentation(options, enhanceNow: active, refresh: true)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        visible = false
        zoomView.updatePresentation(options, enhanceNow: false)
        zoomView.resetZoom()
    }
}

// MARK: - Zoomable page

/// A page image that can be pinch-zoomed and double-tapped (UIScrollView).
///
/// Taps on the page-turning edges are handled the moment the finger lifts.
/// Only taps in the middle wait briefly to see whether a second tap
/// (double-tap zoom) follows, instead of every tap waiting for it.
final class ZoomableImageView: ReaderPageZoomView {
    private let spinner = UIActivityIndicatorView(style: .large)
    /// Shown when the page couldn't be loaded: tapping it loads it again.
    private let retryButton: UIButton = {
        var configuration = UIButton.Configuration.gray()
        configuration.title = String(localized: "Retry")
        configuration.subtitle = String(localized: "Couldn't load the image.")
        configuration.image = UIImage(systemName: "arrow.clockwise")
        configuration.imagePadding = 10
        configuration.cornerStyle = .capsule
        configuration.baseForegroundColor = .white
        configuration.baseBackgroundColor = UIColor(white: 1, alpha: 0.16)
        return UIButton(configuration: configuration)
    }()
    private var retryLoad: (() -> Void)?
    private var loadTask: Task<Void, Never>?
    private var enhanceTask: Task<Void, Never>?
    private var originals: [UIImage] = []
    private var urls: [URL] = []
    private var options: ReaderImageOptions?
    private var rightToLeft = false
    private var enhanceNow = false
    private var renderedImages: [ObjectIdentifier] = []
    private var renderedViewport: CGSize?
    private var pendingTap: DispatchWorkItem?
    private var lastTapTime: CFTimeInterval = 0
    private var lastTapPoint: CGPoint = .zero
    /// Its own delegate: a scroll view is the delegate of its own pan and
    /// pinch, which overriding the delegate methods here would change.
    private let swipeDelegate = SwipeDelegate()
    private lazy var pageSwipe = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
    /// The tap's position in fractions of the page's width and height.
    var onTap: ((CGPoint) -> Void)?
    var onSwipe: ((UISwipeGestureRecognizer.Direction) -> Void)?
    /// The movement of a page swipe when the finger lifts.
    var onSwipeTurn: ((CGPoint) -> Bool)?

    private static let doubleTapInterval: CFTimeInterval = 0.28

    override init(frame: CGRect) {
        super.init(frame: frame)
        spinner.color = .lightGray
        spinner.hidesWhenStopped = true
        addSubview(spinner)

        retryButton.isHidden = true
        retryButton.addAction(UIAction { [weak self] _ in
            guard let self, let again = retryLoad else { return }
            hideFailure()
            again()
        }, for: .touchUpInside)
        addSubview(retryButton)

        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
        swipeDelegate.isBlocked = { [weak self] in
            guard let self else { return true }
            return sizeStatus.blocksNavigation
        }
        pageSwipe.delegate = swipeDelegate
        pageSwipe.maximumNumberOfTouches = 1
        addGestureRecognizer(pageSwipe)
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard gesture.state == .ended, !sizeStatus.blocksNavigation else { return }
        let translation = gesture.translation(in: self)
        if onSwipeTurn?(translation) == true { return }
        guard let direction = ReaderSwipe.direction(translation, in: bounds.size) else { return }
        onSwipe?(direction)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        loadTask?.cancel()
        enhanceTask?.cancel()
        pendingTap?.cancel()
    }

    func updatePresentation(_ options: ReaderImageOptions, enhanceNow: Bool, refresh: Bool = false) {
        guard refresh || self.options != options || self.enhanceNow != enhanceNow else { return }
        self.options = options
        self.enhanceNow = enhanceNow
        enhanceTask?.cancel()
        enhanceTask = nil
        refreshPresentation(animated: false)
    }

    private func refreshPresentation(animated: Bool) {
        guard let options, !originals.isEmpty else { return }
        let images = originals
        let plans = urls.map { options.enhancement(for: $0, pagesPerTurn: images.count) }
        let cached = zip(images, plans).map { image, plan in plan?.cached(image) }
        show(zip(images, cached).map { $1 ?? $0 }, options: options, animated: animated)
        guard enhanceNow, zip(plans, cached).contains(where: { $0 != nil && $1 == nil }) else { return }
        enhanceTask = Task { [weak self] in
            var ready = zip(images, cached).map { $1 ?? $0 }
            for index in images.indices {
                guard !Task.isCancelled else { return }
                if cached[index] == nil, let plan = plans[index], let image = await plan.enhance(images[index]) {
                    ready[index] = image
                }
            }
            guard !Task.isCancelled, let self else { return }
            self.enhanceTask = nil
            self.show(ready, options: options, animated: true)
        }
    }

    private func show(_ images: [UIImage], options: ReaderImageOptions, animated: Bool) {
        guard let first = images.first else { return }
        let identifiers = images.map(ObjectIdentifier.init)
        guard renderedImages != identifiers || renderedViewport != options.viewport else { return }
        renderedImages = identifiers
        renderedViewport = options.viewport
        let image: UIImage
        if images.count == 2 {
            image = Self.join(rightToLeft ? images[1] : first, rightToLeft ? first : images[1],
                              gap: 6, screen: options.viewport, scale: options.displayScale)
        } else {
            image = first
        }
        spinner.stopAnimating()
        if animated {
            UIView.transition(with: imageView, duration: 0.2, options: [.transitionCrossDissolve, .allowUserInteraction]) {
                self.display(image)
            }
        } else {
            display(image)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        spinner.center = CGPoint(x: bounds.midX, y: bounds.midY)
        retryButton.sizeToFit()
        retryButton.center = spinner.center
    }

    private func showFailure(again: @escaping () -> Void) {
        spinner.stopAnimating()
        retryLoad = again
        retryButton.isHidden = false
        setNeedsLayout()
    }

    private func hideFailure() {
        retryLoad = nil
        retryButton.isHidden = true
    }

    override func resetZoom() {
        pendingTap?.cancel()
        pendingTap = nil
        super.resetZoom()
    }

    func load(_ requested: [URL?], rightToLeft: Bool, options: ReaderImageOptions) {
        loadTask?.cancel()
        enhanceTask?.cancel()
        enhanceTask = nil
        self.options = options
        self.rightToLeft = rightToLeft
        urls = requested.compactMap { $0 }
        originals = []
        renderedImages = []
        renderedViewport = nil
        resetZoom()
        display(nil)
        hideFailure()
        let requests = urls.map { AutoCropProcessor.request($0, crop: options.crop) }
        guard !requests.isEmpty else { return }
        let cached = requests.compactMap { request -> UIImage? in
            guard let value = ImagePipeline.shared.cache[request], !value.isPreview else { return nil }
            return value.image
        }
        if cached.count == requests.count {
            originals = cached
            refreshPresentation(animated: false)
            return
        }
        spinner.startAnimating()
        loadTask = Task { [weak self] in
            do {
                let images: [UIImage]
                if requests.count == 2 {
                    async let first = ImagePipeline.shared.image(for: requests[0])
                    async let second = ImagePipeline.shared.image(for: requests[1])
                    images = try await [first, second]
                } else {
                    images = [try await ImagePipeline.shared.image(for: requests[0])]
                }
                guard !Task.isCancelled, let self else { return }
                self.originals = images
                self.refreshPresentation(animated: true)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.showFailure { [weak self] in
                    guard let self, let current = self.options else { return }
                    self.load(requested, rightToLeft: rightToLeft, options: current)
                }
            }
        }
    }

    /// The two pages side by side, [gap] points apart as they show on a
    /// screen of [screen] pixels (the gap is black, like the reader).
    private static func join(_ left: UIImage, _ right: UIImage, gap: CGFloat, screen: CGSize, scale screenScale: CGFloat) -> UIImage {
        let height = max(left.size.height, right.size.height)
        let leftWidth = left.size.width * height / max(1, left.size.height)
        let rightWidth = right.size.width * height / max(1, right.size.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // Keeps the joined image a sensible size for memory.
        let scale = min(1, 4096 / max(1, leftWidth + rightWidth))
        let pagesWidth = (leftWidth + rightWidth) * scale
        let pagesHeight = height * scale
        // The image is fitted to the screen: a gap of [gap] points on screen
        // is this many pixels in the image.
        var gapPixels: CGFloat = 0
        if gap > 0, screen.width > 0, screen.height > 0 {
            let gapOnScreen = gap * max(screenScale, 1)
            // Solve fit = min(W / (w + g / fit), H / h) for the shown gap.
            let fitByHeight = screen.height / pagesHeight
            let fitByWidth = (screen.width - gapOnScreen) / pagesWidth
            let fit = max(min(fitByHeight, fitByWidth), 0.01)
            gapPixels = (gapOnScreen / fit).rounded()
        }
        let size = CGSize(width: pagesWidth + gapPixels, height: pagesHeight)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            left.draw(in: CGRect(x: 0, y: 0, width: leftWidth * scale, height: pagesHeight))
            right.draw(in: CGRect(x: leftWidth * scale + gapPixels, y: 0, width: rightWidth * scale, height: pagesHeight))
        }
    }

    override func sizeDidChange(_ status: ReaderSizeStatus) {
        pageSwipe.isEnabled = !status.blocksNavigation
        if status.adjusting {
            pendingTap?.cancel()
            pendingTap = nil
        }
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard bounds.width > 0, !sizeStatus.adjusting else { return }
        let location = gesture.location(in: self)
        // Position on screen (the scroll view's bounds move when zoomed).
        let point = CGPoint(x: location.x - bounds.minX, y: location.y - bounds.minY)
        let fraction = CGPoint(x: point.x / bounds.width, y: point.y / max(bounds.height, 1))
        if !sizeStatus.blocksNavigation, ReaderTapZones.forward(at: fraction, rightToLeft: false) != nil {
            pendingTap?.cancel()
            pendingTap = nil
            onTap?(fraction)
            return
        }

        let now = CACurrentMediaTime()
        let dx = point.x - lastTapPoint.x
        let dy = point.y - lastTapPoint.y
        if let pending = pendingTap, now - lastTapTime < Self.doubleTapInterval, dx * dx + dy * dy < 3600 {
            pending.cancel()
            pendingTap = nil
            toggleZoom(at: gesture.location(in: imageView))
            return
        }

        pendingTap?.cancel()
        lastTapTime = now
        lastTapPoint = point
        let work = DispatchWorkItem { [weak self] in
            self?.pendingTap = nil
            self?.onTap?(CGPoint(x: 0.5, y: 0.5))
        }
        pendingTap = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.doubleTapInterval, execute: work)
    }

    private func toggleZoom(at point: CGPoint) {
        guard imageView.image != nil else { return }
        let next = sizeStatus.adjusted ? ReaderPageSizing.normalScale : 2.5
        setArtworkScale(next, centeredAt: point, animated: true)
    }
}

/// Keeps the edge swipe-back working when a pushed screen hides its
/// navigation bar (the reader with its controls hidden).
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}


/// Inverts the pages' colors when on.
private struct InvertIf: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled { content.colorInvert() } else { content }
    }
}

/// Go to Page (a number) and the notice after saving a page.
private struct PageAlerts: ViewModifier {
    @Binding var askPage: Bool
    @Binding var pageText: String
    @Binding var notice: String?
    let pageCount: Int
    let go: (Int) -> Void

    func body(content: Content) -> some View {
        content
            .alert("Page Number", isPresented: $askPage) {
                TextField("1 – \(pageCount)", text: $pageText).keyboardType(.numberPad)
                Button("Go") {
                    if let n = Int(pageText), n >= 1, n <= pageCount { go(n - 1) }
                }
                Button("Cancel", role: .cancel) {}
            }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("OK", role: .cancel) {}
            }
    }
}

// MARK: - Return to the current page's thumbnail

/// The gallery scrolls behind the reader as the current page changes.
@MainActor
@Observable
final class ReaderProgress {
    struct ReadingPoint: Equatable {
        let page: Int
        private let id = UUID()
    }

    static let shared = ReaderProgress()
    private var points: [String: ReadingPoint] = [:]

    func begin(_ page: Int, gallery: String) {
        points[gallery] = ReadingPoint(page: page)
    }

    func track(_ page: Int, gallery: String) {
        if points[gallery]?.page != page { points[gallery] = ReadingPoint(page: page) }
    }

    func point(gallery: String) -> ReadingPoint? { points[gallery] }
}

struct ReaderDestination: View {
    let route: ReaderRoute
    let namespace: Namespace.ID
    @State private var page: Int

    init(route: ReaderRoute, namespace: Namespace.ID) {
        self.route = route
        self.namespace = namespace
        _page = State(initialValue: route.startPage)
    }

    var body: some View {
        let gallery = route.source.item.transitionKey
        ReaderView(route: route, page: Binding(
            get: { page },
            set: { value in
                ReaderProgress.shared.track(value, gallery: gallery)
                page = value
            }
        ))
        .navigationTransition(.zoom(sourceID: ReaderThumbnailID(gallery: gallery, page: route.startPage), in: namespace))
    }
}

extension View {
    @ViewBuilder
    func readerThumbnailSource(gallery: String, page: Int, in namespace: Namespace.ID?) -> some View {
        let id = ReaderThumbnailID(gallery: gallery, page: page)
        if let namespace {
            background(ReaderThumbnailAnchor(id: id))
                .matchedTransitionSource(id: id, in: namespace)
        } else {
            self
        }
    }

    /// Keeps thumbnails (ids: page indexes) centred on the page being read.
    func followsReader(gallery: String, position: Binding<Int?>) -> some View {
        modifier(FollowsReader(gallery: gallery, position: position))
    }
}

private struct FollowsReader: ViewModifier {
    let gallery: String
    @Binding var position: Int?
    @State private var progress = ReaderProgress.shared

    func body(content: Content) -> some View {
        ScrollViewReader { proxy in
            content
                .scrollPosition(id: $position, anchor: .center)
                .background(ReaderThumbnailList(gallery: gallery) { reveal($0, using: proxy) }.frame(width: 0, height: 0))
                .onChange(of: progress.point(gallery: gallery), initial: true) { _, point in
                    if let point { reveal(point.page, using: proxy) }
                }
        }
    }

    private func reveal(_ page: Int, using proxy: ScrollViewProxy) {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            position = page
            proxy.scrollTo(page, anchor: .center)
        }
    }
}
