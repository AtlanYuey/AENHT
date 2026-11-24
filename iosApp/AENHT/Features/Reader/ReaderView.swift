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
    /// Its small picture (the slider's preview), when the site gives one.
    var thumb: URL? = nil
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
    /// The UI tests' pages (ReaderFixture).
    init(testPages: [ReaderPage]) {
        id = 1
        title = "Reader fixture"
        pages = testPages
        shareURL = URL(fileURLWithPath: "/reader-fixture")
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
                       aspectRatio: page.aspectRatio,
                       thumb: page.thumbURL)
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
}

// MARK: - Reader

struct ReaderView: View {
    let source: ReaderSource

    @State private var page: Int
    @State private var scrollRequest: ReaderScrollRequest
    @State private var chromeVisible = true
    @State private var autoRead = false
    @State private var showSettings = false
    /// Auto Read's speed and options (a long press on its button).
    @State private var showAutoReadOptions = false
    @State private var sliderValue: Double
    @State private var sliderEditing = false
    /// Hides the controls a while after the last touch.
    @State private var hider = ChromeHider()
    @State private var awake = ScreenAwake()
    /// The page was changed with the controls, which then stay.
    @State private var turnFromPanel = false
    @State private var isLandscape = false
    @State private var viewportPixels: CGSize = .zero
    @Environment(\.privacyMode) private var privacy
    @State private var askPage = false
    @State private var pageText = ""
    @State private var notice: String?
    /// The shown page's size (paged modes) against its fitted one.
    @State private var pageZoom: ReaderZoom = .fitted
    /// The continuous modes' page width against the default (a whole page on screen).
    @State private var stripScale: CGFloat
    /// The strip is narrower or wider than the screen: a sideways drag moves it (not a swipe action).
    @State private var stripMovable = false
    /// Counts Reset Size taps (paged modes).
    @State private var sizeReset = 0

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

    init(route: ReaderRoute) {
        source = route.source
        let start = min(max(0, route.page), max(0, route.source.pages.count - 1))
        _page = State(initialValue: start)
        _scrollRequest = State(initialValue: ReaderScrollRequest(page: start))
        _sliderValue = State(initialValue: Double(start))
        // Keep Page Size: the width set last time.
        let settings = AppSettings.shared
        _stripScale = State(initialValue: settings.keepContinuousPageSize ? CGFloat(settings.continuousPageScale) : 1)
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

    /// The pages are bigger than usual: page turns and the back swipe wait.
    private var isEnlarged: Bool {
        settings.readMode.isPaged ? pageZoom == .enlarged : stripScale > 1.01
    }

    /// The pages aren't at their usual size: Reset Size shows.
    private var isResized: Bool {
        settings.readMode.isPaged ? pageZoom != .fitted : abs(stripScale - 1) > 0.01
    }

    private func resetSize() { sizeReset += 1 }

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
                ReaderGestureLock.mode = settings.readMode
                autoRead = false
                pageZoom = .fitted
                scrollRequest = ReaderScrollRequest(page: page)
                prefetch(around: page)
            }
            // Enlarged, the back swipe from the edge waits (the finger moves the page).
            .onChange(of: isEnlarged) { ReaderGestureLock.enlarged = isEnlarged }
            .onChange(of: stripScale) {
                if settings.keepContinuousPageSize { settings.continuousPageScale = Double(stripScale) }
            }
            .onDisappear { ReaderGestureLock.enlarged = false }
            .task(id: autoRead) { await runAutoRead() }
            .onAppear {
                AppChrome.shared.readersOpen += 1
                ReaderGestureLock.mode = settings.readMode
            }
            .onDisappear { AppChrome.shared.readersOpen = max(0, AppChrome.shared.readersOpen - 1) }
            .onAppear {
                awake.touched()
                ReaderProgress.shared.set(page, gallery: source.item.transitionKey)
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
            .sheet(isPresented: $showAutoReadOptions) {
                AutoReadOptionsSheet()
                    .presentationDetents([.height(300), .medium])
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
                guard chromeVisible else {
                    // The hand: the side touched while the controls were away (the
                    // tap that brings them); their buttons go there.
                    let left = touch.location.x < canvasSize.width / 2
                    if handOnLeft != left { handOnLeft = left }
                    return
                }
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
            .onChange(of: showAutoReadOptions) { scheduleHide() }
            .onChange(of: askPage) { if askPage { autoRead = false } }
            .onChange(of: scenePhase) { if scenePhase != .active { autoRead = false } }
            .onChange(of: autoRead) { if autoRead { chromeVisible = false } }
            .onChange(of: page) { _, new in
                ReaderProgress.shared.set(new, gallery: source.item.transitionKey)
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
            // Back from the left edge only: a swipe in the middle is the reader's.
            .background(EdgeOnlyBackSwipe().frame(width: 0, height: 0))
            // The Apple Pencil's double-tap and squeeze (Controls › Apple Pencil).
            .background(PencilGestures(onDoubleTap: { pencil(settings.pencilDoubleTap) },
                                       onSqueeze: { pencil(settings.pencilSqueeze) }))
            // A keyboard's or page turner's keys (Controls › Keyboard and Page Turner).
            .onAppear {
                ExternalKeyboards.shared.onKey = { key in
                    guard settings.pageKeys else { return }
                    turnByDevice(forward: PageKey.forward(key, rightToLeft: isRTL, swap: settings.pageKeysSwap))
                }
            }
            .onDisappear { ExternalKeyboards.shared.onKey = nil }
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
                canvasSize = size
                viewportPixels = CGSize(width: size.width * displayScale, height: size.height * displayScale)
            }
            .overlay(alignment: .top) {
                // A tap on the status bar does nothing here: the system's would scroll
                // the strip back to the top, a near miss of the back button under it.
                StatusBarTapCatcher().frame(height: 1)
            }
            .ignoresSafeArea()
            .navigationBarTitleDisplayMode(.inline)
            .barToolbar {
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
                    .contentShape(Rectangle())
                    // A double tap on the title: the first page. Counted from single
                    // taps (a toolbar item gets those reliably).
                    .onTapGesture { topTaps.tap(goToStart) }
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
                }
            }
            .toolbar(chromeVisible ? .visible : .hidden, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
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

    @ViewBuilder
    private var content: some View {
        if settings.readMode.isPaged {
            let spreads = spreads
            PagedReader(units: units(spreads), mode: settings.readMode, imageOptions: imageOptions,
                        isActive: scenePhase == .active, page: Binding(
                get: { spreads.map { Int($0.slotOf(page: Int32(page))) } ?? page },
                set: { slot in
                    let first = spreads.map { Int($0.firstPage(slot: Int32(slot))) } ?? slot
                    if (spreads.map { Int($0.slotOf(page: Int32(page))) } ?? page) != slot { page = first }
                }
            ), onTap: { point in
                handleTap(at: point)
            }, onSwipe: { direction in
                handleSwipe(direction)
            }, onSwipeTurn: { forward in
                chromeVisible = false
                go(step(forward: forward))
            }, sizeReset: sizeReset, onZoom: { zoom in
                if pageZoom != zoom { pageZoom = zoom }
            }, onCloseDragBegan: {
                chromeVisible = false
            }, onClose: {
                dismiss()
            })
            .id("\(settings.readMode.rawValue)-\(spreads == nil ? 1 : 2)-\(settings.doublePageCover)-\(settings.doublePageSingleStep)")
        } else {
            ContinuousReader(pages: source.pages, imageOptions: imageOptions, page: $page,
                             request: scrollRequest, running: $autoRead, scale: $stripScale,
                             movable: $stripMovable, resetRequest: sizeReset) {
                chromeVisible = false
            }
            .contentShape(Rectangle())
            .onTapGesture { location in
                let point = CGPoint(x: location.x / max(canvasSize.width, 1), y: location.y / max(canvasSize.height, 1))
                if ReaderTapZones.togglesControls(at: point, paged: false) { chromeVisible.toggle() }
            }
            .gesture(ReaderPan { handleSwipe($0) })
        }
    }

    /// The controls hide on their own a while (Reading settings, 3 s at
    /// least) after they were last touched, unless the slider or the
    /// settings are in use.
    private func scheduleHide() {
        guard chromeVisible, !sliderEditing, !showSettings, !showAutoReadOptions else {
            hider.cancel()
            return
        }
        hider.schedule(after: settings.readerControlsDelay) {
            if !sliderEditing, !showSettings { chromeVisible = false }
        }
    }

    /// The Apple Pencil's double-tap or squeeze: what Controls › Apple Pencil says.
    /// An enlarged page turns as it does from the edges: not (the finger moves it).
    private func pencil(_ action: PencilAction) {
        switch action {
        case .none: break
        case .nextPage, .previousPage:
            turnByDevice(forward: action == .nextPage)
        case .controls: chromeVisible.toggle()
        case .autoRead: autoRead.toggle()
        }
    }

    /// A page turn from a device (the pencil, a keyboard or page turner):
    /// a page; in the continuous modes a screen. Not while a page is enlarged
    /// (as from the edges) or a sheet or the page number prompt is open.
    private func turnByDevice(forward: Bool) {
        guard !showSettings, !askPage else { return }
        if settings.readMode.isPaged {
            guard !isEnlarged else { return }
            go(step(forward: forward))
        } else {
            autoRead = false
            scrollRequest = ReaderScrollRequest(page: page, screens: forward ? 1 : -1)
        }
    }

    /// A tap at [point] (fractions of the page's width and height).
    private func handleTap(at point: CGPoint) {
        guard let forward = ReaderTapZones.forward(at: point, rightToLeft: isRTL) else {
            chromeVisible.toggle()
            return
        }
        go(step(forward: forward))
    }

    /// Swipes do what the Controls settings say: up and down with the left
    /// and right tap zones, left and right with the top and bottom ones. A
    /// swipe along the way the pages move turns or scrolls them instead.
    private func handleSwipe(_ direction: UISwipeGestureRecognizer.Direction) {
        // A strip that moves sideways: the drag moved it.
        if !settings.readMode.isPaged, stripMovable { return }
        // Pages turned left and right: a swipe up or down that closes was the drag that closed it.
        if settings.readMode == .leftToRight || settings.readMode == .rightToLeft,
           direction == .up || direction == .down,
           PagedReader.Coordinator.closes(up: direction == .up) { return }
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
        case .exit: dismiss()
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

    /// The reader's size in points (where a tap is, as a fraction of it).
    @State private var canvasSize: CGSize = .zero
    /// The side of the screen last touched to bring the controls (their buttons go there).
    @State private var handOnLeft = false
    /// The slider's track in the control panel (the pages around the finger go over it).
    @State private var trackFrame: CGRect = .zero
    @State private var stripWidth: CGFloat = 0
    private var ehThumbs = EhThumbs.shared

    /// The title's taps, counted to find a double tap.
    @State private var topTaps = DoubleTapCounter()

    /// Double tap on the top (Controls › Double Tap Top to Go to Start): back to the first page.
    private func goToStart() {
        #if DEBUG
        ReaderDebugMetrics.shared.note("top:double")
        #endif
        guard settings.doubleTapTopToStart, page != 0 || !settings.readMode.isPaged else { return }
        // Like Go to Page: the controls stay.
        turnFromPanel = true
        autoRead = false
        go(0)
    }

    private func go(_ target: Int, automatic: Bool = false) {
        guard (0..<pageCount).contains(target) else { return }
        if !automatic { autoRead = false }
        if settings.readMode.isPaged {
            page = target
        } else {
            scrollRequest = ReaderScrollRequest(page: target, animated: settings.animatePageTurns)
        }
    }

    /// A page turn from the controls, which stay on screen.
    private func goFromPanel(_ target: Int) {
        guard (0..<pageCount).contains(target), target != page else { return }
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
        guard autoRead, settings.readMode.isPaged else { return }
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
        VStack(spacing: 0) {
            // While the slider is held: the pages around it, over the finger.
            if sliderEditing { scrubStrip } else { floatingButtons }
            pageBar
        }
        .coordinateSpace(.named("readerPanel"))
        .frame(maxWidth: 640)
    }

    /// The page shown in the bar: the slider's while it's held.
    private var shownPage: Int { sliderEditing ? Int(sliderValue.rounded()) : page }

    /// The page slider (right to left for right-to-left reading): held, the
    /// page and the pages around follow it; let go, the reader goes there.
    private var pageSlider: some View {
        Slider(
            value: $sliderValue,
            in: 0...Double(max(1, pageCount - 1)),
            step: 1,
            onEditingChanged: { editing in
                sliderEditing = editing
                if !editing { goFromPanel(Int(sliderValue.rounded())) }
            }
        )
        .environment(\.layoutDirection, isRTL ? .rightToLeft : .leftToRight)
    }

    /// One row: the page, the slider, the pages left (as much room above as below).
    /// Turning is a tap or a swipe on the page: no arrows here.
    private var pageBar: some View {
        HStack(spacing: 12) {
            Text(verbatim: "\(shownPage + 1) / \(pageCount)")
                .font(.footnote.weight(.semibold).monospacedDigit())
                .frame(minWidth: 56, alignment: .leading)
            pageSlider
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("readerPanel")) } action: { trackFrame = $0 }
            Text("Pages left: \(max(0, pageCount - 1 - shownPage))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .lineLimit(1)
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .modifier(GlassPanel(cornerRadius: 26))
        // The whole panel takes touches: one that misses the slider doesn't
        // reach the page under it (which would hide the controls).
        .contentShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    /// Reader Settings and Auto Read float on the sides above the bar: one
    /// alone on the hand's side; both, Auto Read where Controls › Auto Read
    /// Button Side says. Reset Size in the middle, when the pages were resized.
    @ViewBuilder private var floatingButtons: some View {
        let showSettingsButton = settings.readerSettingsButton
        let showAutoRead = settings.autoReadButton
        let autoLeft: Bool = switch settings.autoReadSide {
        case .hand: handOnLeft
        case .left: true
        case .right: false
        }
        let autoOnLeft = showSettingsButton ? autoLeft : handOnLeft
        let settingsOnLeft = showAutoRead ? !autoLeft : handOnLeft
        if showSettingsButton || showAutoRead || isResized {
            ZStack {
                HStack(spacing: 10) {
                    if showSettingsButton, settingsOnLeft { settingsButton }
                    if showAutoRead, autoOnLeft { autoReadButton }
                    Spacer(minLength: 0)
                }
                if isResized {
                    // Pinched smaller or bigger: back to the usual size.
                    Button { resetSize() } label: {
                        Image(systemName: "arrow.up.left.and.down.right.magnifyingglass").floatingTarget()
                    }
                    .accessibilityLabel("Reset Size")
                    .transition(.opacity)
                }
                HStack(spacing: 10) {
                    Spacer(minLength: 0)
                    if showAutoRead, !autoOnLeft { autoReadButton }
                    if showSettingsButton, !settingsOnLeft { settingsButton }
                }
            }
            .buttonStyle(.borderless)
            .font(.body.weight(.medium))
            .padding(.horizontal, 16)
        }
    }

    private var settingsButton: some View {
        Button { showSettings = true } label: {
            Image(systemName: "slider.horizontal.3").floatingTarget()
        }
        .accessibilityLabel("Reader Settings")
    }

    /// A tap starts or stops; a long press shows the speed and the options.
    private var autoReadButton: some View {
        Image(systemName: autoRead ? "pause.fill" : "play.fill")
            .floatingTarget()
            .onTapGesture { autoRead.toggle() }
            .onLongPressGesture(minimumDuration: 0.4) { showAutoReadOptions = true }
            .accessibilityElement()
            .accessibilityLabel(autoReadTitle)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { autoRead.toggle() }
            .accessibilityAction(named: Text(settings.readMode.isPaged ? "Auto Read" : "Auto Scroll")) { showAutoReadOptions = true }
    }

    /// Five pages (two before, the one under the finger, two after), centred
    /// over the slider's thumb.
    private var scrubStrip: some View {
        let current = min(max(Int(sliderValue.rounded()), 0), max(pageCount - 1, 0))
        let around = Array(max(0, current - 2)...max(0, min(pageCount - 1, current + 2)))
        let indices = isRTL ? Array(around.reversed()) : around
        let fraction = pageCount > 1 ? CGFloat(current) / CGFloat(pageCount - 1) : 0
        // The thumb's centre: the track less the thumb's own half at each end.
        let inset: CGFloat = 14
        let thumbX = trackFrame.minX + inset + max(0, trackFrame.width - 2 * inset) * (isRTL ? 1 - fraction : fraction)
        return GeometryReader { geometry in
            let left = min(max(0, thumbX - stripWidth / 2), max(0, geometry.size.width - stripWidth))
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(indices, id: \.self) { index in scrubThumb(index, current: index == current) }
            }
            .padding(8)
            .modifier(GlassPanel(cornerRadius: 18))
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { stripWidth = $0 }
            .offset(x: left)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(height: 140)
        .allowsHitTesting(false)
        .task(id: current) {
            // E-Hentai: the thumbnails of pages not seen yet, once the finger rests a moment.
            guard source.item.site == .ehentai, let token = source.item.token else { return }
            try? await Task.sleep(for: .milliseconds(250))
            for index in indices where !Task.isCancelled {
                if source.pages[index].url?.isFileURL == true { continue }
                if ehThumbs.thumbnail(gid: source.item.id, page: index + 1) != nil { continue }
                await EhPageDirectory.shared.loadThumbnail(gid: source.item.id, token: token, page: index + 1)
            }
        }
    }

    private func scrubThumb(_ index: Int, current: Bool) -> some View {
        VStack(spacing: 3) {
            pageThumb(index)
                .frame(width: current ? 64 : 46, height: current ? 90 : 64)
                .background(Color(white: 0.17))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                .overlay {
                    if current { RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(.white, lineWidth: 2) }
                }
            Text(verbatim: "\(index + 1)")
                .font(.caption2.weight(current ? .semibold : .regular).monospacedDigit())
                .foregroundStyle(.white.opacity(current ? 1 : 0.6))
        }
    }

    /// A page's small picture: the downloaded file, its thumbnail, or its number.
    @ViewBuilder private func pageThumb(_ index: Int) -> some View {
        let readerPage = source.pages[index]
        if let url = readerPage.url, url.isFileURL {
            RemoteImage(url: url, targetWidth: 120)
        } else if let thumb = readerPage.thumb {
            RemoteImage(url: thumb, targetWidth: 120)
        } else if source.item.site == .ehentai, let thumb = ehThumbs.thumbnail(gid: source.item.id, page: index + 1) {
            EhThumbnailView(thumb: thumb)
        } else {
            Text(verbatim: "\(index + 1)").font(.caption).foregroundStyle(.white.opacity(0.5))
        }
    }
}

private extension View {
    /// A floating round button above the control bar (44 pt).
    func floatingTarget() -> some View {
        frame(width: 44, height: 44)
            .modifier(GlassPanel(cornerRadius: 22))
            .contentShape(Circle())
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

/// Auto Read's interval (paged) or Auto Scroll's speed and whether it goes
/// on while dragging (continuous): in Reading settings, and in the reader
/// when its Auto Read button is long-pressed.
struct AutoReadOptionsSection: View {
    @Bindable var settings: AppSettings

    var body: some View {
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
        } header: {
            Text(settings.readMode.isPaged ? "Auto Read" : "Auto Scroll")
        }
    }
}

/// The reader's Auto Read options, from a long press on its button (it keeps going).
struct AutoReadOptionsSheet: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form { AutoReadOptionsSection(settings: settings) }
                .navigationTitle(settings.readMode.isPaged ? "Auto Read" : "Auto Scroll")
                .navigationBarTitleDisplayMode(.inline)
                .barToolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", systemImage: "checkmark") { dismiss() }
                    }
                }
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
        default: controls
        }
    }

    /// Reading: how the pages go (direction, double page, gap), how they
    /// turn, auto read / auto scroll, and loading ahead.
    @ViewBuilder private var reading: some View {
        Section {
            Picker("Reading Direction", selection: $settings.readMode) {
                ForEach(ReadMode.allCases) { mode in
                    Label(mode.title, systemImage: mode.systemImage).tag(mode)
                }
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
        }
        // How a swipe and a tap turn the pages, chosen apart (which taps turn: Controls).
        if settings.readMode.isPaged {
            Section {
                Picker("Swipe to Turn", selection: $settings.swipeTurn) {
                    ForEach(SwipeTurn.allCases) { Text($0.title).tag($0) }
                }
                Picker("Tap to Turn", selection: $settings.tapTurn) {
                    ForEach(TapTurn.allCases) { Text($0.title).tag($0) }
                }
            } header: {
                Text("Page Turns")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    switch settings.swipeTurn {
                    case .sweep: Text("Swipe Fade: the pages don't move; the page fades away with a soft edge that follows your finger. Stop and it stops; a quick swipe turns at once.")
                    case .slide: Text("Slide: the pages slide with your finger.")
                    }
                    switch settings.tapTurn {
                    case .spread: Text("Spread Fade: a tap turns the page with a fade spreading from where you tapped.")
                    case .fade: Text("Page Fade: a tap fades the whole page into the next.")
                    case .slide: Text("A tap slides the next page in.")
                    }
                }
            }
        }
        AutoReadOptionsSection(settings: settings)
        Section {
            Stepper(value: $settings.preloadPages, in: 1...10) {
                LabeledContent("Preload Pages", value: "\(settings.preloadPages)")
            }
        } footer: {
            Text("Pages ahead of the current one are downloaded in advance.")
        }
    }

    /// Display: the pages' look and size, super resolution, the screen.
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
        // The pages' size: cut margins, smaller than the screen, kept from one gallery to the next.
        if settings.readMode.isPaged {
            Section {
                Toggle("Auto Crop Margins", isOn: $settings.autoCrop)
                Toggle("Allow Smaller Pages", isOn: $settings.allowSmallerPages)
            } header: {
                Text("Page Size")
            } footer: {
                Text("Pinching in can make a page smaller than the screen, with black around it. Off, a page fills the screen at least; pinching only enlarges it.")
            }
        } else {
            Section {
                Toggle("Keep Page Size", isOn: $settings.keepContinuousPageSize)
            } header: {
                Text("Page Size")
            } footer: {
                Text("Pinch with two fingers to make the pages smaller or bigger. With Keep Page Size, that size stays for every page and the next galleries; drag the black sides to move the pages. Reset Size, at the bottom of the reader, brings back the usual size.")
            }
        }
        SuperResolutionSection(settings: settings)
        Section {
            Picker("Keep Screen On", selection: $settings.screenOn) {
                ForEach(ScreenOn.allCases) { Text($0.title).tag($0) }
            }
        } footer: {
            Text("The reader keeps the screen on this long after the last touch, then leaves it to the system's own auto-lock (it never turns the screen off sooner than the system would).")
        }
    }

    /// Controls: the control bar (what it shows, when it shows), the taps and
    /// swipes that turn pages, then the devices in use (a keyboard or page
    /// turner, Apple Pencil).
    @ViewBuilder private var controls: some View {
        Section {
            Toggle("Reader Settings Button", isOn: $settings.readerSettingsButton)
            Toggle(settings.readMode.isPaged ? "Auto Read Button" : "Auto Scroll Button", isOn: $settings.autoReadButton)
            if settings.readerSettingsButton, settings.autoReadButton {
                Picker(settings.readMode.isPaged ? "Auto Read Button Side" : "Auto Scroll Button Side", selection: $settings.autoReadSide) {
                    ForEach(AutoReadSide.allCases) { Text($0.title).tag($0) }
                }
            }
            Toggle("Tap the Middle to Show Controls", isOn: $settings.controlsMiddleOnly)
        } header: {
            Text("Control Bar")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text("The buttons float above the slider: one alone on the side of your hand (where you tapped to show the controls); both, Auto Read on the side chosen and Reader Settings on the other. Reader Settings is also in the menu at the top right.")
                Text("Long-press the Auto Read button for its speed and options; a tap starts or stops it.")
                Text("Tap the Middle to Show Controls: a tap on either side (where the tap zones are; left and right when scrolling) doesn't show or hide the controls.")
            }
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
        Section {
            Toggle("Double Tap Top to Go to Start", isOn: $settings.doubleTapTopToStart)
        } footer: {
            Text("Double-tap the title at the top of the reader to go back to the first page.")
        }
        // Taps, tap zones and swipes turn pages: the continuous modes scroll instead.
        if settings.readMode.isPaged {
            Section {
                Toggle("Tap to Turn Pages", isOn: $settings.tapToTurnPages)
                Picker("Tap Zones", selection: $settings.tapZones) {
                    ForEach(TapZones.allCases) { Text($0.title).tag($0) }
                }
            } footer: {
                Text("How a tap turns the page (slide, page fade or spread fade) is set in Reading › Tap to Turn.")
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
        // A connected keyboard or page turner, and a pencil once it has been used here.
        PageKeysSection()
        if PencilSupport.current != nil, settings.pencilSeen != .nothing {
            ApplePencilSection()
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

    /// Whether [point] (fractions of the page) is on a side, not the middle:
    /// the outer 30 % where the tap zones are (left and right in the
    /// continuous modes). With Controls › Tap the Middle to Show Controls, a
    /// tap there doesn't show or hide the controls.
    @MainActor
    static func isSide(_ point: CGPoint, paged: Bool) -> Bool {
        if paged, AppSettings.shared.tapZones == .topBottom {
            return point.y < edge || point.y > 1 - edge
        }
        return point.x < edge || point.x > 1 - edge
    }

    /// Whether a tap at [point] that doesn't turn the page shows or hides the controls.
    @MainActor
    static func togglesControls(at point: CGPoint, paged: Bool) -> Bool {
        !(AppSettings.shared.controlsMiddleOnly && isSide(point, paged: paged))
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

/// Swipes over the continuous reader, alongside its scrolling.
struct ReaderPan: UIGestureRecognizerRepresentable {
    let action: (UISwipeGestureRecognizer.Direction) -> Void

    func makeCoordinator(converter: CoordinateSpaceConverter) -> SwipeDelegate {
        SwipeDelegate()
    }

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let recognizer = UIPanGestureRecognizer()
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        guard recognizer.state == .ended, let view = recognizer.view,
              let direction = ReaderSwipe.direction(recognizer.translation(in: view), in: view.bounds.size) else { return }
        action(direction)
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

// MARK: - Paged reader (UIPageViewController)

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
    /// The old page fades away in a circle growing from [point] (fractions of the page): a tap.
    case spread(CGPoint)
}

/// One page at a time, left-to-right, right-to-left or top-to-bottom.
/// UIPageViewController keeps the neighbouring pages alive, so turning a
/// page shows an already decoded image instead of a blank one.
struct PagedReader: UIViewControllerRepresentable {
    /// One or two pages (double page) per turn.
    let units: [[ReaderPage]]
    let mode: ReadMode
    let imageOptions: ReaderImageOptions
    let isActive: Bool
    @Binding var page: Int
    let onTap: (CGPoint) -> Void
    let onSwipe: (UISwipeGestureRecognizer.Direction) -> Void
    /// Fade on Swipe: a swipe the way the pages go turns them (forward or not).
    var onSwipeTurn: (Bool) -> Void = { _ in }
    /// Counts the reader's Reset Size taps: the page goes back to its size.
    var sizeReset = 0
    /// The shown page became fitted, smaller or enlarged.
    var onZoom: (ReaderZoom) -> Void = { _ in }
    /// A drag up or down that closes the reader began (the controls hide) / closes it.
    var onCloseDragBegan: () -> Void = {}
    var onClose: () -> Void = {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let controller = UIPageViewController(
            transitionStyle: .scroll,
            navigationOrientation: mode == .verticalPaged ? .vertical : .horizontal,
            options: [.interPageSpacing: 16]
        )
        controller.view.backgroundColor = .black
        if #available(iOS 26.0, *) {
            // The bar's edge over the pages: soft (the system took a hard band for the page view).
            for scroll in controller.view.subviews.compactMap({ $0 as? UIScrollView }) {
                scroll.topEdgeEffect.style = .soft
                scroll.bottomEdgeEffect.style = .soft
            }
        }
        context.coordinator.pageViewController = controller
        context.coordinator.addCloseDrag(to: controller.view)
        context.coordinator.addSweepDrag(to: controller.view)
        context.coordinator.lastSizeReset = sizeReset
        // Swipe Fade: without a data source the pages can't be dragged (the swipe fades them).
        controller.dataSource = AppSettings.shared.swipeTurn == .sweep ? nil : context.coordinator
        controller.delegate = context.coordinator
        if let first = context.coordinator.controller(for: page) {
            controller.setViewControllers([first], direction: .forward, animated: false)
        }
        context.coordinator.prepare(after: page)
        context.coordinator.updatePaging()
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        (controller.viewControllers?.first as? PageController)?.configure(imageOptions, active: isActive)
        if coordinator.lastSizeReset != sizeReset {
            // Reset Size: this page, and the ones after it, at their size.
            coordinator.lastSizeReset = sizeReset
            coordinator.smaller = 1
            (controller.viewControllers?.first as? PageController)?.setSize(1, animated: true)
        }
        defer { coordinator.prepare(after: page) }
        let sweeps = AppSettings.shared.swipeTurn == .sweep
        if (controller.dataSource == nil) != sweeps { controller.dataSource = sweeps ? nil : coordinator }
        coordinator.updatePaging()
        let pending = coordinator.pendingTurn
        coordinator.pendingTurn = nil
        guard let current = (controller.viewControllers?.first as? PageController)?.index,
              current != page
        else { return }
        // A turn of one page as Tap to Turn says (a tap's spread starts at the
        // finger, a page button's in the middle); a jump (the slider) shows the page at once.
        let turn: PageTurn
        if abs(page - current) != 1 {
            turn = .instant
        } else if let pending {
            turn = pending
        } else {
            switch AppSettings.shared.tapTurn {
            case .slide: turn = .slide
            case .fade: turn = .fade
            case .spread: turn = .spread(CGPoint(x: 0.5, y: 0.5))
            }
        }
        coordinator.show(page, from: current, in: controller, turn: turn)
    }

    static func dismantleUIViewController(_ controller: UIPageViewController, coordinator: Coordinator) {
        coordinator.stopPreparation()
    }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate, UIGestureRecognizerDelegate {
        var parent: PagedReader
        /// The page turn drawn over the page view controller, if one runs.
        private var running: (id: Int, cover: UIView, page: UIView)?
        private var turnID = 0
        /// Page controllers near the current page, reused so a tap shows
        /// the very page that was already laid out next to this one.
        private var cache: [Int: PageController] = [:]
        private let imagePreparation = ReaderImagePreparation()
        /// How the next turn is drawn when a tap asked for it (Swipe + Spread: from the finger).
        var pendingTurn: PageTurn?
        /// A Swipe Fade turn following the finger.
        private var sweeping: Sweep?
        private var sweepDrag: UIPanGestureRecognizer?

        private struct Sweep {
            let cover: UIView
            let mask: CAGradientLayer
            /// The mask's position with nothing of the page gone, and how far it goes.
            let from: CGPoint
            let distance: CGPoint
            let length: CGFloat
            /// The finger goes left or up (the progress counts that way).
            let toStart: Bool
            let original: PageController
            let target: Int
            var progress: CGFloat = 0
            var finishing = false
        }
        weak var pageViewController: UIPageViewController?
        var lastSizeReset = 0
        private var zoom: ReaderZoom = .fitted
        private var closeDrag: UIPanGestureRecognizer?
        /// A page made smaller makes the next ones as small (until Reset Size).
        var smaller: CGFloat = 1

        init(_ parent: PagedReader) {
            self.parent = parent
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

        /// The pages slide under the finger only with Swipe to Turn › Slide,
        /// and not while the page is enlarged (it moves instead). With Swipe
        /// Fade nothing moves under the finger: no bounce either.
        func updatePaging() {
            // The gate and the back swipe ask the shown page itself.
            ReaderGestureLock.enlargedNow = { [weak self] in self?.enlargedNow ?? false }
            let enlarged = zoom == .enlarged || enlargedNow
            // Enlarged, one finger only moves the page: the reader's own drags are off.
            sweepDrag?.isEnabled = !enlarged
            closeDrag?.isEnabled = !enlarged
            guard let pages = pageViewController?.view.subviews.compactMap({ $0 as? UIScrollView }).first else { return }
            let slides = AppSettings.shared.swipeTurn == .slide && !enlarged
            if pages.isScrollEnabled != slides { pages.isScrollEnabled = slides }
            pages.scrollsToTop = false
            // A drag from the left edge is the swipe back (the system's drag, following
            // the finger), not a slide to the page before: the slide waits for it.
            for drag in ReaderGestureLock.systemDrags where !waitingFor.contains(where: { $0 === drag }) {
                pages.panGestureRecognizer.require(toFail: drag)
                waitingFor.append(drag)
            }
        }
        private var waitingFor: [UIGestureRecognizer] = []

        // MARK: Swipe Fade, following the finger

        func addSweepDrag(to view: UIView) {
            let drag = UIPanGestureRecognizer(target: self, action: #selector(sweepDragged(_:)))
            drag.maximumNumberOfTouches = 1
            drag.delegate = self
            view.addGestureRecognizer(drag)
            sweepDrag = drag
        }

        private var vertical: Bool { parent.mode == .verticalPaged }

        /// Starts the turn the finger began: the next page goes in place
        /// under a picture of this one, which the finger fades away.
        private func beginSweep(along: CGFloat, in container: UIView) {
            cutTurns()
            guard let pageController = pageViewController,
                  let current = pageController.viewControllers?.first as? PageController,
                  let old = current.view else { return }
            let toStart = along < 0
            let forward = parent.mode == .rightToLeft ? !toStart : toStart
            let target = current.index + (forward ? 1 : -1)
            guard parent.units.indices.contains(target), let next = controller(for: target),
                  let cover = old.snapshotView(afterScreenUpdates: false) else { return }
            cover.frame = old.convert(old.bounds, to: container)
            cover.isUserInteractionEnabled = false
            pageController.setViewControllers([next], direction: .forward, animated: false)
            container.addSubview(cover)
            let size = cover.bounds.size
            let length = vertical ? size.height : size.width
            let soft = length * 0.22
            let span = length + soft
            let mask = CAGradientLayer()
            mask.frame = vertical
                ? CGRect(x: 0, y: toStart ? 0 : -soft, width: size.width, height: span)
                : CGRect(x: toStart ? 0 : -soft, y: 0, width: span, height: size.height)
            let opaque = UIColor.black.cgColor
            let clear = UIColor.clear.cgColor
            mask.colors = toStart ? [opaque, opaque, clear] : [clear, opaque, opaque]
            mask.locations = toStart ? [0, NSNumber(value: Double(length / span)), 1] : [0, NSNumber(value: Double(soft / span)), 1]
            mask.startPoint = vertical ? CGPoint(x: 0.5, y: 0) : CGPoint(x: 0, y: 0.5)
            mask.endPoint = vertical ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 1, y: 0.5)
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            cover.layer.mask = mask
            CATransaction.commit()
            let distance = toStart ? -span : span
            sweeping = Sweep(cover: cover, mask: mask, from: mask.position,
                             distance: vertical ? CGPoint(x: 0, y: distance) : CGPoint(x: distance, y: 0),
                             length: length, toStart: toStart, original: current, target: target)
        }

        private func setSweep(_ progress: CGFloat) {
            guard var sweep = sweeping else { return }
            sweep.progress = min(max(progress, 0), 1)
            sweeping = sweep
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            sweep.mask.position = CGPoint(x: sweep.from.x + sweep.distance.x * sweep.progress,
                                          y: sweep.from.y + sweep.distance.y * sweep.progress)
            CATransaction.commit()
        }

        /// The finger lifted: on to the next page, or back (it went back, or not far and slowly).
        private func endSweep(velocity: CGFloat) {
            guard var sweep = sweeping, !sweep.finishing else { return }
            let turns = velocity < -250 ? false : (sweep.progress > 0.35 || velocity > 350)
            sweep.finishing = true
            sweeping = sweep
            let end: CGFloat = turns ? 1 : 0
            let remaining = abs(end - sweep.progress) * sweep.length
            // At the finger's speed, quick but never a jump.
            let duration = min(0.3, max(0.12, Double(remaining / max(abs(velocity), 1400))))
            let to = CGPoint(x: sweep.from.x + sweep.distance.x * end, y: sweep.from.y + sweep.distance.y * end)
            CATransaction.begin()
            CATransaction.setCompletionBlock { [weak self] in self?.finishSweep(turned: turns) }
            let move = CABasicAnimation(keyPath: "position")
            move.fromValue = NSValue(cgPoint: sweep.mask.position)
            move.toValue = NSValue(cgPoint: to)
            move.duration = duration
            move.timingFunction = CAMediaTimingFunction(name: .easeOut)
            sweep.mask.position = to
            sweep.mask.add(move, forKey: "sweep")
            CATransaction.commit()
        }

        /// Ends the Swipe Fade turn (at once, when another turn starts).
        private func finishSweep(turned: Bool) {
            guard let sweep = sweeping else { return }
            sweeping = nil
            sweep.mask.removeAllAnimations()
            if !turned {
                // Back: the old page goes in place again under the (whole) picture of it.
                pageViewController?.setViewControllers([sweep.original], direction: .forward, animated: false)
            }
            sweep.cover.removeFromSuperview()
            if turned {
                if parent.page != sweep.target { parent.page = sweep.target }
                prepare(after: sweep.target)
            }
        }

        /// A turn starting while another is drawn: that one ends at once, where it was going.
        private func cutTurns() {
            // A Swipe Fade turn on its way: done; one still under a finger: back.
            if let sweep = sweeping { finishSweep(turned: sweep.finishing && sweep.progress > 0) }
            endTurn()
        }

        @objc private func sweepDragged(_ drag: UIPanGestureRecognizer) {
            #if DEBUG
            ReaderDebugMetrics.shared.event("sweep", drag)
            #endif
            guard let view = drag.view else { return }
            let t = drag.translation(in: view)
            let v = drag.velocity(in: view)
            let along = vertical ? t.y : t.x
            let speed = vertical ? v.y : v.x
            switch drag.state {
            case .began:
                parent.onCloseDragBegan()
                beginSweep(along: along != 0 ? along : speed, in: view)
            case .changed:
                guard let sweep = sweeping, !sweep.finishing else { return }
                // Forward the way the finger first went; back to the start, nothing gone.
                setSweep((sweep.toStart ? -along : along) / max(sweep.length, 1))
            case .ended:
                guard let sweep = sweeping else { return }
                endSweep(velocity: sweep.toStart ? -speed : speed)
            default:
                if sweeping != nil { endSweep(velocity: -1000) }
            }
        }

        // MARK: Closing by dragging up or down

        /// Left and right turn pages: a drag up or down (whichever Controls ›
        /// Swipes sets to close) closes the reader, the page following the
        /// finger. Only at the usual size or smaller: an enlarged page moves.
        func addCloseDrag(to view: UIView) {
            let drag = UIPanGestureRecognizer(target: self, action: #selector(closeDragged(_:)))
            drag.maximumNumberOfTouches = 1
            drag.delegate = self
            view.addGestureRecognizer(drag)
            closeDrag = drag
        }

        /// Whether a swipe that way closes the reader (Controls › Swipes).
        static func closes(up: Bool) -> Bool {
            let settings = AppSettings.shared
            guard settings.tapZones == .leftRight else { return false }
            return (up ? settings.swipeUp : settings.swipeDown) == .exit
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            if gestureRecognizer === sweepDrag, let drag = sweepDrag, let view = drag.view {
                guard AppSettings.shared.swipeTurn == .sweep, zoom != .enlarged, !enlargedNow else { return false }
                let velocity = drag.velocity(in: view)
                let along = vertical ? velocity.y : velocity.x
                let across = vertical ? velocity.x : velocity.y
                guard abs(along) > abs(across) * 1.2 else { return false }
                // From the left edge it is the swipe back.
                let start = drag.location(in: view).x - drag.translation(in: view).x
                return vertical || start > ReaderGestureLock.edge
            }
            guard gestureRecognizer === closeDrag, let drag = closeDrag, let view = drag.view else { return true }
            guard parent.mode == .leftToRight || parent.mode == .rightToLeft, zoom != .enlarged, !enlargedNow else { return false }
            let velocity = drag.velocity(in: view)
            guard abs(velocity.y) > abs(velocity.x) * 1.3 else { return false }
            // Down is the zoom transition's own drag when it is there (it reveals the gallery page and can be taken back).
            if velocity.y > 0, ReaderGestureLock.systemCloses { return false }
            return Self.closes(up: velocity.y < 0)
        }

        @objc private func closeDragged(_ drag: UIPanGestureRecognizer) {
            #if DEBUG
            ReaderDebugMetrics.shared.event("close", drag)
            #endif
            guard let view = drag.view else { return }
            let t = drag.translation(in: view)
            let height = max(view.bounds.height, 1)
            switch drag.state {
            case .began:
                parent.onCloseDragBegan()
            case .changed:
                // Smaller the further it goes, following the finger.
                let scale = max(0.55, 1 - abs(t.y) / height * 0.7)
                view.transform = CGAffineTransform(translationX: t.x, y: t.y).scaledBy(x: scale, y: scale)
            case .ended:
                let v = drag.velocity(in: view)
                let far = abs(t.y) > height * 0.12 || abs(v.y) > 800
                let sameWay = t.y == 0 || (t.y < 0) == (v.y <= 0) || abs(v.y) < 200
                if far, sameWay, Self.closes(up: t.y < 0) {
                    // Closed from where the page is: it zooms on into its thumbnail.
                    parent.onClose()
                } else {
                    springBack(view)
                }
            default:
                springBack(view)
            }
        }

        private func springBack(_ view: UIView) {
            UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.85, initialSpringVelocity: 0,
                           options: [.allowUserInteraction]) { view.transform = .identity }
        }

        /// The shown page's size: enlarged, it can't be turned by dragging
        /// (the finger moves it); smaller, the next pages come as small.
        /// The shown page is enlarged, asked of the page itself.
        private var enlargedNow: Bool {
            (pageViewController?.viewControllers?.first as? PageController)?.isEnlarged ?? false
        }

        private func zoomChanged(_ zoom: ReaderZoom, scale: CGFloat) {
            self.zoom = zoom
            switch zoom {
            case .smaller: smaller = scale
            case .fitted: smaller = 1
            case .enlarged: break
            }
            updatePaging()
            parent.onZoom(zoom)
        }

        /// A swipe of [t] ended: with Fade on Swipe, one the way the pages go
        /// turns them (left or up is forward, except right to left). Answers
        /// whether it did.
        func swipeTurn(_ t: CGPoint) -> Bool { false }

        func controller(for index: Int) -> PageController? {
            guard parent.units.indices.contains(index) else { return nil }
            if let cached = cache[index] {
                cached.configure(parent.imageOptions, active: parent.isActive)
                return cached
            }
            let controller = PageController(index: index, urls: parent.units[index].map(\.url),
                                            rightToLeft: parent.mode == .rightToLeft,
                                            options: parent.imageOptions, active: parent.isActive)
            controller.onTap = { [weak self] point in
                guard let self else { return }
                // Spread Fade: a tap's turn spreads from the finger.
                if AppSettings.shared.tapTurn == .spread { pendingTurn = .spread(point) }
                parent.onTap(point)
            }
            controller.onSwipe = { [weak self] direction in self?.parent.onSwipe(direction) }
            controller.onSwipeTurn = { [weak self] t in self?.swipeTurn(t) ?? false }
            controller.startingSize = { [weak self] in AppSettings.shared.allowSmallerPages ? self?.smaller ?? 1 : 1 }
            controller.onZoom = { [weak self] zoom, scale in self?.zoomChanged(zoom, scale: scale) }
            cache[index] = controller
            let current = parent.page
            cache = cache.filter { (current - 1...current + ReaderImagePreparation.turns).contains($0.key) || $0.key == index }
            return controller
        }

        /// Shows [index]. The page view controller switches to it at once and
        /// the turn is drawn over it: the old page (a snapshot) slides away
        /// as the new one slides in, or fades out over it. A turn asked for
        /// while another runs ends that one at once and starts its own, so
        /// fast taps keep turning a page each instead of waiting and jumping.
        func show(_ index: Int, from current: Int, in pageController: UIPageViewController, turn style: PageTurn) {
            guard let target = controller(for: index) else { return }
            let forward = index > current
            let direction: UIPageViewController.NavigationDirection
            if parent.mode == .rightToLeft {
                direction = forward ? .reverse : .forward
            } else {
                direction = forward ? .forward : .reverse
            }
            cutTurns()
            let container = pageController.view!
            var cover: UIView?
            if style != .instant, let old = pageController.viewControllers?.first?.view,
               let snapshot = old.snapshotView(afterScreenUpdates: false) {
                snapshot.frame = old.convert(old.bounds, to: container)
                // Taps go through to the new page (fast turns).
                snapshot.isUserInteractionEnabled = false
                cover = snapshot
            }
            pageController.setViewControllers([target], direction: direction, animated: false)
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
            if case let .spread(point) = style {
                spread(cover, from: point) { [weak self] in self?.endTurn(id) }
                return
            }
            // The new page comes in from the side the turn goes to.
            let vertical = parent.mode == .verticalPaged
            let distance = (vertical ? container.bounds.height : container.bounds.width) + 16
            let offset = direction == .forward ? distance : -distance
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

        /// The old page ([cover]) fades away in a circle growing from [point]
        /// (fractions of the page) with a soft edge, until none of it is left.
        private func spread(_ cover: UIView, from point: CGPoint, done: @escaping () -> Void) {
            let size = cover.bounds.size
            let center = CGPoint(x: size.width * min(max(point.x, 0), 1), y: size.height * min(max(point.y, 0), 1))
            // To the farthest corner, and the soft edge past it.
            let corners = [CGPoint.zero, CGPoint(x: size.width, y: 0), CGPoint(x: 0, y: size.height), CGPoint(x: size.width, y: size.height)]
            let reach = corners.map { hypot($0.x - center.x, $0.y - center.y) }.max() ?? 0
            let soft: CGFloat = 0.28
            let radius = reach / (1 - soft)
            let mask = CAGradientLayer()
            mask.type = .radial
            mask.frame = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
            mask.startPoint = CGPoint(x: 0.5, y: 0.5)
            mask.endPoint = CGPoint(x: 1, y: 1)
            let opaque = UIColor.black.cgColor
            let clear = UIColor.clear.cgColor
            mask.colors = [clear, clear, opaque, opaque]
            let start: [NSNumber] = [0, 0, NSNumber(value: Double(soft) * 0.4), 1]
            let end: [NSNumber] = [0, NSNumber(value: Double(1 - soft)), 1, 1]
            mask.locations = start
            cover.layer.mask = mask
            CATransaction.begin()
            CATransaction.setCompletionBlock(done)
            let grow = CABasicAnimation(keyPath: "locations")
            grow.fromValue = start
            grow.toValue = end
            grow.duration = 0.42
            grow.timingFunction = CAMediaTimingFunction(controlPoints: 0.3, 0, 0.2, 1)
            mask.locations = end
            mask.add(grow, forKey: "spread")
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

        private func neighbour(of controller: UIViewController, _ delta: Int) -> UIViewController? {
            guard let page = controller as? PageController else { return nil }
            let step = parent.mode == .rightToLeft ? -delta : delta
            return self.controller(for: page.index + step)
        }

        func pageViewController(_ pageViewController: UIPageViewController,
                                viewControllerBefore viewController: UIViewController) -> UIViewController? {
            neighbour(of: viewController, -1)
        }

        func pageViewController(_ pageViewController: UIPageViewController,
                                viewControllerAfter viewController: UIViewController) -> UIViewController? {
            neighbour(of: viewController, 1)
        }

        func pageViewController(_ pageViewController: UIPageViewController,
                                didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController],
                                transitionCompleted completed: Bool) {
            guard completed, let current = pageViewController.viewControllers?.first as? PageController else {
                return
            }
            if parent.page != current.index {
                parent.page = current.index
            }
            prepare(after: current.index)
        }
    }
}

final class PageController: UIViewController {
    let index: Int
    /// One page, or two shown side by side.
    let urls: [URL?]
    let rightToLeft: Bool
    var onTap: ((CGPoint) -> Void)?
    var onSwipe: ((UISwipeGestureRecognizer.Direction) -> Void)?
    /// Fade on Swipe: a swipe's translation when it ended; answers whether it turned the page.
    var onSwipeTurn: ((CGPoint) -> Bool)?
    /// The shown page's size changed (fitted, smaller, enlarged), and its zoom.
    var onZoom: ((ReaderZoom, CGFloat) -> Void)?
    /// The size a page shows at when it comes (the last page's, when made smaller).
    var startingSize: () -> CGFloat = { 1 }
    private let zoomView = ZoomableImageView()
    private var options: ReaderImageOptions
    private var active: Bool
    private var visible = false

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
        zoomView.onTap = { [weak self] point in self?.onTap?(point) }
        zoomView.onSwipe = { [weak self] direction in self?.onSwipe?(direction) }
        zoomView.onSwipeTurn = { [weak self] t in self?.onSwipeTurn?(t) ?? false }
        zoomView.onZoom = { [weak self] zoom in
            guard let self, visible else { return }
            #if DEBUG
            ReaderDebugMetrics.shared.note("zoom:\(zoom)")
            #endif
            onZoom?(zoom, zoomView.zoomScale)
        }
        zoomView.load(urls, rightToLeft: rightToLeft, options: options)
    }

    /// Its page is enlarged now (not as last reported).
    var isEnlarged: Bool { isViewLoaded && zoomView.isEnlarged }

    /// Back to the fitted size (the reader's Reset Size), or [scale].
    func setSize(_ scale: CGFloat, animated: Bool) {
        guard isViewLoaded else { return }
        zoomView.setSize(scale, animated: animated)
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
        // As small as the page before it, when that one was made smaller.
        zoomView.setSize(startingSize(), animated: false)
        zoomView.reportCurrent()
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
final class ZoomableImageView: UIScrollView, UIScrollViewDelegate {
    private let imageView = UIImageView()
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
    /// The tap's position in fractions of the page's width and height.
    var onTap: ((CGPoint) -> Void)?
    var onSwipe: ((UISwipeGestureRecognizer.Direction) -> Void)?
    /// A swipe's translation when it ended; answers whether it turned the page (Fade on Swipe).
    var onSwipeTurn: ((CGPoint) -> Bool)?
    /// The page's size changed between fitted, smaller and enlarged.
    var onZoom: ((ReaderZoom) -> Void)?
    private var reportedZoom: ReaderZoom = .fitted
    /// The size the page was fitted to (its zoom is 1 there).
    private var fittedSize: CGSize = .zero

    private static let doubleTapInterval: CFTimeInterval = 0.28
    /// Enlarged: page turns and the back swipe wait until it is back to its size.
    var isEnlarged: Bool { zoomScale > 1.01 }

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        // A tap on the status bar doesn't move the page (StatusBarTapCatcher has it).
        scrollsToTop = false
        if #available(iOS 26.0, *) {
            // Under the top bar the page fades and blurs softly (iOS 26), not a hard band.
            topEdgeEffect.style = .soft
            bottomEdgeEffect.style = .soft
        }
        // Never bouncing at its size: only an enlarged page moves (the page view turns the pages).
        alwaysBounceHorizontal = false
        alwaysBounceVertical = false
        // Pinched smaller down to a size that still reads (readableMinimum), not out of the reader.
        minimumZoomScale = ReaderZoom.smallestPaged
        maximumZoomScale = 4
        bouncesZoom = true
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        backgroundColor = .black

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        addSubview(imageView)

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
            return isEnlarged
        }
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = swipeDelegate
        addGestureRecognizer(pan)

        // An enlarged page moves under one finger with the reader's own drag:
        // the scroll view's own one didn't move it inside the page view (it
        // began and ended with the whole distance, the page staying put).
        panGestureRecognizer.isEnabled = false
        movePan.maximumNumberOfTouches = 1
        movePan.delegate = moveDelegate
        moveDelegate.shouldBegin = { [weak self] in self?.isEnlarged ?? false }
        addGestureRecognizer(movePan)
    }

    // MARK: Moving an enlarged page

    private lazy var movePan = UIPanGestureRecognizer(target: self, action: #selector(moved(_:)))
    private let moveDelegate = MoveDelegate()
    private var moveStart: CGPoint = .zero
    private var glide: CADisplayLink?
    private var glideVelocity: CGPoint = .zero
    private var glideTime: CFTimeInterval = 0

    private final class MoveDelegate: NSObject, UIGestureRecognizerDelegate {
        var shouldBegin: () -> Bool = { false }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool { shouldBegin() }
    }

    private var maxOffset: CGPoint {
        CGPoint(x: max(0, contentSize.width - bounds.width), y: max(0, contentSize.height - bounds.height))
    }

    /// [value] within 0…[limit], giving way less and less past the ends while the finger pulls.
    private static func rubber(_ value: CGFloat, _ limit: CGFloat, _ size: CGFloat) -> CGFloat {
        if value < 0 { return -size * (1 - 1 / (-value * 0.55 / max(size, 1) + 1)) }
        if value > limit { return limit + size * (1 - 1 / ((value - limit) * 0.55 / max(size, 1) + 1)) }
        return value
    }

    @objc private func moved(_ pan: UIPanGestureRecognizer) {
        let t = pan.translation(in: self)
        switch pan.state {
        case .began:
            stopGlide()
            moveStart = contentOffset
        case .changed:
            let limit = maxOffset
            contentOffset = CGPoint(x: Self.rubber(moveStart.x - t.x, limit.x, bounds.width),
                                    y: Self.rubber(moveStart.y - t.y, limit.y, bounds.height))
            #if DEBUG
            ReaderDebugMetrics.shared.record(self)
            #endif
        case .ended:
            let v = pan.velocity(in: self)
            startGlide(CGPoint(x: -v.x, y: -v.y))
        default:
            settle()
        }
    }

    /// On at the finger's speed, slowing down as a scroll view does, then back inside the page.
    private func startGlide(_ velocity: CGPoint) {
        stopGlide()
        let limit = maxOffset
        let outside = contentOffset.x < 0 || contentOffset.y < 0 || contentOffset.x > limit.x || contentOffset.y > limit.y
        guard !outside, hypot(velocity.x, velocity.y) > 40 else { settle(); return }
        glideVelocity = velocity
        glideTime = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(glided(_:)))
        link.add(to: .main, forMode: .common)
        glide = link
    }

    @objc private func glided(_ link: CADisplayLink) {
        let now = CACurrentMediaTime()
        let dt = min(now - glideTime, 1.0 / 30)
        glideTime = now
        let decay = CGFloat(pow(Double(UIScrollView.DecelerationRate.normal.rawValue), dt * 1000))
        glideVelocity = CGPoint(x: glideVelocity.x * decay, y: glideVelocity.y * decay)
        let limit = maxOffset
        var next = CGPoint(x: contentOffset.x + glideVelocity.x * CGFloat(dt), y: contentOffset.y + glideVelocity.y * CGFloat(dt))
        // At an end the page stops there (no flying past it).
        if next.x < 0 || next.x > limit.x { next.x = min(max(next.x, 0), limit.x); glideVelocity.x = 0 }
        if next.y < 0 || next.y > limit.y { next.y = min(max(next.y, 0), limit.y); glideVelocity.y = 0 }
        contentOffset = next
        if hypot(glideVelocity.x, glideVelocity.y) < 8 { stopGlide() }
    }

    private func stopGlide() {
        glide?.invalidate()
        glide = nil
    }

    /// Back inside the page when pulled past an end.
    private func settle() {
        let limit = maxOffset
        let inside = CGPoint(x: min(max(contentOffset.x, 0), limit.x), y: min(max(contentOffset.y, 0), limit.y))
        guard inside != contentOffset else { return }
        UIView.animate(withDuration: 0.35, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0,
                       options: [.allowUserInteraction, .beginFromCurrentState]) { self.contentOffset = inside }
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard gesture.state == .ended else { return }
        let translation = gesture.translation(in: self)
        // Fade on Swipe: a swipe the way the pages go turns them, however slow.
        if onSwipeTurn?(translation) == true { return }
        guard let direction = ReaderSwipe.direction(translation, in: bounds.size) else { return }
        onSwipe?(direction)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        glide?.invalidate()
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
                self.imageView.image = image
            }
        } else {
            imageView.image = image
        }
        updateMinimum()
        if zoomScale < minimumZoomScale { setZoomScale(minimumZoomScale, animated: false) }
        #if DEBUG
        ReaderDebugMetrics.shared.note(String(format: "show min%.2f max%.2f", minimumZoomScale, maximumZoomScale))
        #endif
    }

    #if DEBUG
    private var watching = false
    @objc private func debugPinch(_ g: UIGestureRecognizer) { ReaderDebugMetrics.shared.event("pinch", g) }
    @objc private func debugPan(_ g: UIGestureRecognizer) {
        ReaderDebugMetrics.shared.event("pan", g)
        guard g.state == .ended, let pan = g as? UIPanGestureRecognizer else { return }
        // How far the finger went for this view, and how big this view is on the screen.
        let t = pan.translation(in: self)
        let onScreen = convert(CGRect(x: 0, y: 0, width: 100, height: 100), to: nil).width
        var ancestors = ""
        var view: UIView? = self
        while let current = view {
            if !current.transform.isIdentity { ancestors += "T:" + String(describing: type(of: current)) + " " }
            if let scroll = current as? UIScrollView, scroll !== self, scroll.zoomScale != 1 {
                ancestors += String(format: "Z%.2f:", scroll.zoomScale) + String(describing: type(of: current)) + " "
            }
            view = current.superview
        }
        ReaderDebugMetrics.shared.note(String(format: "t%.0f,%.0f screen%.0f dragging%d tracking%d ", t.x, t.y, onScreen,
                                              isDragging ? 1 : 0, isTracking ? 1 : 0) + ancestors)
    }
    #endif

    override func layoutSubviews() {
        super.layoutSubviews()
        #if DEBUG
        if ReaderFixture.isActive, !watching, let pinch = pinchGestureRecognizer {
            watching = true
            pinch.addTarget(self, action: #selector(debugPinch(_:)))
            movePan.addTarget(self, action: #selector(debugPan(_:)))
        }
        #endif
        if bounds.size != fittedSize, bounds.width > 0 {
            #if DEBUG
            ReaderDebugMetrics.shared.note(String(format: "fit%.0fx%.0f", bounds.width, bounds.height))
            #endif
            // A new size (turned, the first layout): fitted again, a smaller size kept.
            fittedSize = bounds.size
            let kept = min(zoomScale, 1)
            if zoomScale != 1 { zoomScale = 1 }
            imageView.frame = CGRect(origin: .zero, size: bounds.size)
            contentSize = bounds.size
            updateMinimum()
            if kept < 1 { zoomScale = max(kept, minimumZoomScale) }
            centerImage()
        }
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

    func resetZoom() {
        #if DEBUG
        ReaderDebugMetrics.shared.note("reset")
        #endif
        stopGlide()
        pendingTap?.cancel()
        pendingTap = nil
        if zoomScale != 1 {
            setZoomScale(1, animated: false)
        }
    }

    /// Shows the page at [scale] (1: fitted; less: smaller, as the last page was).
    func setSize(_ scale: CGFloat, animated: Bool) {
        #if DEBUG
        ReaderDebugMetrics.shared.note(String(format: "size%.2f", scale))
        #endif
        let target = min(max(scale, minimumZoomScale), maximumZoomScale)
        if abs(zoomScale - target) > 0.001 { setZoomScale(target, animated: animated) }
        report()
    }

    /// The smallest size: the pages as shown stay readable (see ReaderZoom).
    private func updateMinimum() {
        // Smaller than the screen only when Controls › Allow Smaller Pages says so.
        minimumZoomScale = AppSettings.shared.allowSmallerPages ? ReaderZoom.minimumPaged(fitted: fittedImageSize()) : 1
    }

    /// The image as fitted in the page's bounds (zoom 1).
    private func fittedImageSize() -> CGSize {
        guard let image = imageView.image, image.size.width > 0, image.size.height > 0,
              bounds.width > 0, bounds.height > 0 else { return bounds.size }
        let fit = min(bounds.width / image.size.width, bounds.height / image.size.height)
        return CGSize(width: image.size.width * fit, height: image.size.height * fit)
    }

    private func centerImage() {
        let offsetX = max((bounds.width - contentSize.width) / 2, 0)
        let offsetY = max((bounds.height - contentSize.height) / 2, 0)
        imageView.center = CGPoint(x: contentSize.width / 2 + offsetX, y: contentSize.height / 2 + offsetY)
    }

    private func report() {
        // Scrolling stays on (switching it while pinching ends the pinch and
        // leaves the page stuck): at its size or smaller there is nothing to
        // scroll, so the finger turns pages; enlarged, it moves the page.
        let zoom = ReaderZoom(scale: zoomScale)
        guard zoom != reportedZoom else { return }
        reportedZoom = zoom
        onZoom?(zoom)
    }

    /// Asks again for the page's state (it became the shown page).
    func reportCurrent() {
        reportedZoom = ReaderZoom(scale: zoomScale)
        onZoom?(reportedZoom)
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
        imageView.image = nil
        resetZoom()
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

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView
    }

    func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
        stopGlide()
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centerImage()
        report()
        #if DEBUG
        ReaderDebugMetrics.shared.record(self)
        #endif
    }

    #if DEBUG
    private var seenX: ClosedRange<CGFloat>?
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let x = contentOffset.x
        seenX = seenX.map { min($0.lowerBound, x)...max($0.upperBound, x) } ?? x...x
        ReaderDebugMetrics.shared.record(self)
    }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { seenX = nil }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if let seenX { ReaderDebugMetrics.shared.note(String(format: "x%.0f-%.0f", seenX.lowerBound, seenX.upperBound)) }
    }
    #endif

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        // Almost its size: its size.
        if abs(scale - 1) < 0.04, scale != 1 { setZoomScale(1, animated: true) }
        // The size it was left at (the next pages come as small).
        reportCurrent()
        #if DEBUG
        ReaderDebugMetrics.shared.record(self)
        #endif
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard bounds.width > 0 else { return }
        let location = gesture.location(in: self)
        // Position on screen (the scroll view's bounds move when zoomed).
        let point = CGPoint(x: location.x - bounds.minX, y: location.y - bounds.minY)
        let fraction = CGPoint(x: point.x / bounds.width, y: point.y / max(bounds.height, 1))
        let zoomed = isEnlarged

        if !zoomed, ReaderTapZones.forward(at: fraction, rightToLeft: false) != nil {
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
            // Not a turn: the controls (not from a side, with Tap the Middle to Show Controls).
            guard MainActor.assumeIsolated({ ReaderTapZones.togglesControls(at: fraction, paged: true) }) else { return }
            self?.onTap?(CGPoint(x: 0.5, y: 0.5))
        }
        pendingTap = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.doubleTapInterval, execute: work)
    }

    private func toggleZoom(at point: CGPoint) {
        if abs(zoomScale - 1) > 0.01 {
            setZoomScale(1, animated: true)
        } else {
            let scale: CGFloat = 2.5
            let size = CGSize(width: bounds.width / scale, height: bounds.height / scale)
            zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                            width: size.width, height: size.height), animated: true)
        }
    }
}

// MARK: - Back swipe

/// In the reader a swipe in the middle of the page is the reader's (turning
/// pages, its swipe actions): only the swipe from the left edge goes back.
/// Two system gestures would otherwise take it: iOS 26's swipe back from
/// anywhere on the screen, and the drag that closes a screen opened with the
/// zoom transition (the reader opens out of a page's thumbnail).
private struct EdgeOnlyBackSwipe: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController {
        private var blocked: [UIGestureRecognizer] = []
        private var gated: [UIGestureRecognizer] = []
        private let gate = ReaderCloseGate()
        private var shown = false

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            shown = true
            arrange()
            // The zoom transition adds its gestures when it has finished,
            // possibly after this: looked for again a moment later.
            for delay in [0.15, 0.5, 1.2] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                    guard let self, shown else { return }
                    arrange()
                }
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            shown = false
            // Leaving the reader (or starting to): the other screens have them again.
            for gesture in blocked { gesture.isEnabled = true }
            blocked = []
            ReaderGestureLock.systemDrags = []
            ReaderGestureLock.enlargedNow = { false }
        }

        /// The reader's screen: the navigation controller's child it is in.
        private func readerScreen(_ navigation: UINavigationController) -> UIViewController? {
            var controller: UIViewController = self
            while let parent = controller.parent, parent !== navigation { controller = parent }
            return controller.parent === navigation ? controller : nil
        }

        /// The zoom transition's drag on the reader's screen follows the
        /// finger back into the thumbnail, and can be taken back: it stays,
        /// waiting for the gate, which lets it have only the edge swipe and a
        /// drag down (ReaderGestureLock). Its pinch (it took the pinch that
        /// makes a page smaller) and iOS 26's swipe back from anywhere are
        /// kept from the reader; so are such gestures above the reader's
        /// screen (shared with the other screens, they can't wait for the gate).
        private func arrange() {
            guard let navigation = navigationController, let screen = readerScreen(navigation) else { return }
            let root = screen.view!
            if #available(iOS 26.0, *) { Self.softenEdges(in: root) }
            if gate.view !== root {
                gate.view?.removeGestureRecognizer(gate)
                root.addGestureRecognizer(gate)
            }
            var block: [UIGestureRecognizer] = []
            var wait: [UIGestureRecognizer] = []
            if #available(iOS 26.0, *), let swipe = navigation.interactiveContentPopGestureRecognizer {
                block.append(swipe)
            }
            var view: UIView? = root
            while let current = view, current !== navigation.view.superview {
                for gesture in current.gestureRecognizers ?? [] where Self.isSystemClose(gesture, navigation, gate) {
                    if gesture is UIPinchGestureRecognizer || current !== root {
                        block.append(gesture)
                    } else {
                        wait.append(gesture)
                    }
                }
                view = current.superview
            }
            for gesture in wait where !gated.contains(where: { $0 === gesture }) {
                gesture.require(toFail: gate)
                gated.append(gesture)
            }
            if !gated.isEmpty {
                ReaderGestureLock.systemDrags = gated
                // The edge swipe goes to the drag that follows the finger, not the plain back swipe.
                if #available(iOS 26.0, *), let edge = navigation.interactivePopGestureRecognizer { block.append(edge) }
            }
            var added: [String] = []
            for gesture in block where gesture.isEnabled && !blocked.contains(where: { $0 === gesture }) {
                gesture.isEnabled = false
                blocked.append(gesture)
                added.append(String(reflecting: type(of: gesture)))
            }
            if !added.isEmpty || !wait.isEmpty {
                Diagnostics.shared.log(category: "reader", message: "Reader gestures: kept " + added.joined(separator: ", ")
                                       + "; waiting for the gate " + wait.map { String(reflecting: type(of: $0)) }.joined(separator: ", "))
            }
        }

        /// The top bar's edge over the reader: soft (fading, blurred), as on the app's other screens.
        @available(iOS 26.0, *)
        private static func softenEdges(in view: UIView) {
            if let scroll = view as? UIScrollView, scroll.topEdgeEffect.style != .soft {
                scroll.topEdgeEffect.style = .soft
                scroll.bottomEdgeEffect.style = .soft
            }
            for subview in view.subviews { softenEdges(in: subview) }
        }

        private static func isSystemClose(_ gesture: UIGestureRecognizer, _ navigation: UINavigationController,
                                          _ gate: UIGestureRecognizer) -> Bool {
            if gesture === navigation.interactivePopGestureRecognizer || gesture === gate { return false }
            // With the module (SwiftUI's own classes are named "SwiftUI.…").
            let name = String(reflecting: type(of: gesture))
            let delegate = gesture.delegate.map { String(reflecting: type(of: $0)) } ?? ""
            // SwiftUI's gestures (the reader's taps and swipes) and the reader's own.
            if name.contains("SwiftUI") || delegate.contains("SwiftUI") || gesture.delegate is SwipeDelegate { return false }
            if name.localizedCaseInsensitiveContains("zoom") || delegate.localizedCaseInsensitiveContains("zoom") { return true }
            if delegate.localizedCaseInsensitiveContains("transition") || delegate.localizedCaseInsensitiveContains("dismiss") { return true }
            // Above the reader's pages nothing else pinches or drags.
            return gesture is UIPinchGestureRecognizer || (gesture is UIPanGestureRecognizer && !(gesture is UIScreenEdgePanGestureRecognizer))
        }
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
        // Not while a reader's page is enlarged: the finger moves the page.
        viewControllers.count > 1 && !ReaderGestureLock.isEnlarged
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

// MARK: - Back to the page's thumbnail

/// A page's thumbnail on a gallery page, as a zoom transition source.
struct ReaderThumbID: Hashable {
    /// The gallery on its site (`GalleryItem.transitionKey`).
    let gallery: String
    let page: Int
}

/// The page each open reader is on, so the gallery page behind it keeps its
/// row of pages on that page and the reader closes into its thumbnail.
@MainActor
@Observable
final class ReaderProgress {
    static let shared = ReaderProgress()
    private(set) var pages: [String: Int] = [:]

    func set(_ page: Int, gallery: String) {
        if pages[gallery] != page { pages[gallery] = page }
    }

    func page(gallery: String) -> Int? { pages[gallery] }
}

/// The reader, zooming out of (and back into) the thumbnail of the page it's on.
struct ReaderDestination: View {
    let route: ReaderRoute
    let namespace: Namespace.ID
    @State private var progress = ReaderProgress.shared

    var body: some View {
        ReaderView(route: route)
            .navigationTransition(.zoom(sourceID: ReaderThumbID(gallery: route.source.item.transitionKey,
                                                                  page: progress.page(gallery: route.source.item.transitionKey) ?? route.page),
                                        in: namespace))
    }
}

extension View {
    /// Marks a page thumbnail as where the reader zooms from and back to.
    @ViewBuilder
    func readerThumbSource(gallery: String, page: Int, _ namespace: Namespace.ID?) -> some View {
        if let namespace {
            matchedTransitionSource(id: ReaderThumbID(gallery: gallery, page: page), in: namespace)
        } else {
            self
        }
    }

    /// Keeps a row of pages (ids: page indexes) centred on the page the
    /// reader is on, also while it's open, so it closes into its thumbnail.
    func followsReader(gallery: String, position: Binding<Int?>) -> some View {
        modifier(FollowsReader(gallery: gallery, position: position))
    }
}

private struct FollowsReader: ViewModifier {
    let gallery: String
    @Binding var position: Int?
    @State private var progress = ReaderProgress.shared

    func body(content: Content) -> some View {
        content
            .scrollPosition(id: $position, anchor: .center)
            .onChange(of: progress.page(gallery: gallery)) { _, page in
                if let page { position = page }
            }
    }
}
