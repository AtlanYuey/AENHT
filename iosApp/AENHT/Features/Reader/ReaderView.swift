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
}

// MARK: - Reader

struct ReaderView: View {
    let source: ReaderSource

    @State private var page: Int
    @State private var position: Int?
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
    @Environment(\.privacyMode) private var privacy
    @State private var askPage = false
    @State private var pageText = ""
    @State private var notice: String?

    @Environment(AppSettings.self) private var settings
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale

    @State private var prefetcher = ImagePrefetcher(
        pipeline: .shared, destination: .memoryCache, maxConcurrentRequestCount: 3
    )
    /// What the prefetcher is loading now.
    @State private var prefetched: Set<URL> = []

    init(route: ReaderRoute) {
        source = route.source
        let start = min(max(0, route.page), max(0, route.source.pages.count - 1))
        _page = State(initialValue: start)
        _position = State(initialValue: start)
        _sliderValue = State(initialValue: Double(start))
    }

    private var pageCount: Int { source.pages.count }

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
        content
            // Back from the left edge only: a swipe in the middle is the reader's.
            .background(EdgeOnlyBackSwipe().frame(width: 0, height: 0))
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
                ReaderScreen.pixels = CGSize(width: size.width * displayScale, height: size.height * displayScale)
            }
            .ignoresSafeArea()
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
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
            .onChange(of: sliderEditing) { scheduleHide() }
            .onChange(of: showSettings) { scheduleHide() }
            .onChange(of: page) { _, new in
                ReaderProgress.shared.set(new, gallery: source.item.transitionKey)
                sliderValue = Double(new)
                if position != new { position = new }
                prefetch(around: new)
                // Turning or scrolling pages hides the controls.
                if turnFromPanel {
                    turnFromPanel = false
                } else if !sliderEditing {
                    chromeVisible = false
                }
            }
            .task(id: page) {
                // Saved once the page settles, not on every turn (saving
                // refreshes the history list and costs a frame).
                try? await Task.sleep(for: .milliseconds(700))
                guard !Task.isCancelled else { return }
                recordPage()
            }
            .onChange(of: position) { _, new in
                if let new, new != page { page = new }
            }
            .onChange(of: settings.readMode) { position = page }
            .task(id: autoRead) { await runAutoRead() }
            .onAppear { AppChrome.shared.readersOpen += 1 }
            .onDisappear { AppChrome.shared.readersOpen = max(0, AppChrome.shared.readersOpen - 1) }
            .onAppear {
                awake.touched()
                ReaderProgress.shared.set(page, gallery: source.item.transitionKey)
                prefetch(around: page)
                recordPage()
                scheduleHide()
            }
            .onDisappear {
                hider.cancel()
                awake.stop()
                prefetcher.stopPrefetching()
                recordPage()
            }
            .modifier(PageAlerts(askPage: $askPage, pageText: $pageText, notice: $notice, pageCount: pageCount) { target in
                turnFromPanel = true
                page = target
                position = target
                sliderValue = Double(target)
            })
            .sheet(isPresented: $showSettings) {
                ReaderSettingsSheet()
                    .presentationDetents([.medium, .large])
            }
    }

    // MARK: Pages

    @ViewBuilder
    private var content: some View {
        if settings.readMode.isPaged {
            let spreads = spreads
            PagedReader(units: units(spreads), mode: settings.readMode, page: Binding(
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
            })
            .id("\(settings.readMode.rawValue)-\(spreads == nil ? 1 : 2)-\(settings.doublePageCover)-\(settings.doublePageSingleStep)-\(settings.spreadGapOn ? Int(settings.spreadGap) : 0)")
        } else {
            ScrollView(.vertical) {
                LazyVStack(spacing: settings.readMode == .verticalGap ? settings.pageGap : 0) {
                    ForEach(source.pages) { page in
                        Color.clear
                            .aspectRatio(page.aspectRatio, contentMode: .fit)
                            .overlay {
                                RemoteImage(url: page.url, contentMode: .fit, targetWidth: 1400)
                            }
                            .id(page.index)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollPosition(id: $position, anchor: .top)
            .scrollIndicators(.hidden)
            .onScrollPhaseChange { _, phase in
                if phase == .interacting, chromeVisible { chromeVisible = false }
            }
            .contentShape(Rectangle())
            .onTapGesture { chromeVisible.toggle() }
            .gesture(ReaderPan { handleSwipe($0) })
        }
    }

    /// The controls hide on their own a while (Reading settings, 3 s at
    /// least) after they were last touched, unless the slider or the
    /// settings are in use.
    private func scheduleHide() {
        guard chromeVisible, !sliderEditing, !showSettings else {
            hider.cancel()
            return
        }
        hider.schedule(after: settings.readerControlsDelay) {
            if !sliderEditing, !showSettings { chromeVisible = false }
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

    private func go(_ target: Int) {
        guard (0..<pageCount).contains(target) else { return }
        if settings.readMode.isPaged {
            page = target
        } else if settings.animatePageTurns {
            // Continuous modes scroll, or jump without moving.
            withAnimation(.smooth(duration: 0.3)) { position = target }
        } else {
            position = target
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
        let stale = prefetched.subtracting(urls)
        if !stale.isEmpty { prefetcher.stopPrefetching(with: Array(stale)) }
        prefetched = Set(urls)
        prefetcher.startPrefetching(with: urls)
    }

    private func runAutoRead() async {
        guard autoRead else { return }
        chromeVisible = false
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(settings.autoReadInterval))
            guard !Task.isCancelled else { return }
            let next = step(forward: true)
            guard (0..<pageCount).contains(next) else {
                autoRead = false
                return
            }
            go(next)
        }
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
                .environment(\.layoutDirection, isRTL ? .rightToLeft : .leftToRight)
                Text("\(pageCount)")
                    .font(.footnote.monospacedDigit())
                    .frame(minWidth: 28)
            }
            HStack {
                Button {
                    goFromPanel(step(forward: isRTL))
                } label: {
                    Image(systemName: "chevron.left")
                }
                Spacer()
                Button {
                    autoRead.toggle()
                } label: {
                    Label(autoRead ? "Stop Auto Read" as LocalizedStringKey : "Auto Read",
                          systemImage: autoRead ? "pause.fill" : "play.fill")
                }
                Spacer()
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                Spacer()
                Button {
                    goFromPanel(step(forward: !isRTL))
                } label: {
                    Image(systemName: "chevron.right")
                }
            }
            .font(.body.weight(.medium))
            .labelStyle(.titleAndIcon)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .modifier(GlassPanel())
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: 640)
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
            .toolbar {
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
                    Toggle("Gap Between Pages", isOn: $settings.spreadGapOn)
                    if settings.spreadGapOn {
                        // Like the gap of the continuous mode.
                        VStack(alignment: .leading) {
                            LabeledContent("Page Gap") {
                                Text("\(Int(settings.spreadGap)) pt")
                                    .monospacedDigit()
                            }
                            Slider(value: $settings.spreadGap, in: 2...60, step: 2) {
                                Text("Page Gap")
                            }
                        }
                    }
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
}

/// One page at a time, left-to-right, right-to-left or top-to-bottom.
/// UIPageViewController keeps the neighbouring pages alive, so turning a
/// page shows an already decoded image instead of a blank one.
struct PagedReader: UIViewControllerRepresentable {
    /// One or two pages (double page) per turn.
    let units: [[ReaderPage]]
    let mode: ReadMode
    @Binding var page: Int
    let onTap: (CGPoint) -> Void
    let onSwipe: (UISwipeGestureRecognizer.Direction) -> Void
    /// Fade on Swipe: a swipe the way the pages go turns them (forward or not).
    var onSwipeTurn: (Bool) -> Void = { _ in }

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
        // Fade on Swipe: without a data source the pages can't be dragged (the swipe is the reader's).
        controller.dataSource = AppSettings.shared.swipeFades ? nil : context.coordinator
        controller.delegate = context.coordinator
        if let first = context.coordinator.controller(for: page) {
            controller.setViewControllers([first], direction: .forward, animated: false)
        }
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        let swipeFades = AppSettings.shared.swipeFades
        if (controller.dataSource == nil) != swipeFades { controller.dataSource = swipeFades ? nil : coordinator }
        let sweep = coordinator.pendingSweep
        coordinator.pendingSweep = nil
        guard let current = (controller.viewControllers?.first as? PageController)?.index,
              current != page
        else { return }
        // A swipe (Fade on Swipe) sweeps; a turn of one page slides, or fades
        // without the animation; a jump (the slider) shows the page at once.
        let turn: PageTurn = abs(page - current) != 1 ? .instant
            : sweep ?? (AppSettings.shared.animatePageTurns ? .slide : .fade)
        coordinator.show(page, from: current, in: controller, turn: turn)
    }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var parent: PagedReader
        /// The page turn drawn over the page view controller, if one runs.
        private var running: (id: Int, cover: UIView, page: UIView)?
        private var turnID = 0
        /// Page controllers near the current page, reused so a tap shows
        /// the very page that was already laid out next to this one.
        private var cache: [Int: PageController] = [:]
        /// The sweep of the swipe that asked for the next turn (Fade on Swipe).
        var pendingSweep: PageTurn?

        init(_ parent: PagedReader) {
            self.parent = parent
        }

        /// A swipe of [t] ended: with Fade on Swipe, one the way the pages go
        /// turns them (left or up is forward, except right to left). Answers
        /// whether it did.
        func swipeTurn(_ t: CGPoint) -> Bool {
            guard AppSettings.shared.swipeFades else { return false }
            let vertical = parent.mode == .verticalPaged
            let along = vertical ? t.y : t.x
            let across = vertical ? t.x : t.y
            guard abs(along) >= 40, abs(along) >= 1.5 * abs(across) else { return false }
            pendingSweep = .sweep(vertical: vertical, toStart: along < 0)
            parent.onSwipeTurn(parent.mode == .rightToLeft ? along > 0 : along < 0)
            return true
        }

        func controller(for index: Int) -> PageController? {
            guard parent.units.indices.contains(index) else { return nil }
            if let cached = cache[index] { return cached }
            let controller = PageController(index: index, urls: parent.units[index].map(\.url), rightToLeft: parent.mode == .rightToLeft)
            controller.onTap = { [weak self] point in self?.parent.onTap(point) }
            controller.onSwipe = { [weak self] direction in self?.parent.onSwipe(direction) }
            controller.onSwipeTurn = { [weak self] t in self?.swipeTurn(t) ?? false }
            cache[index] = controller
            let current = parent.page
            cache = cache.filter { abs($0.key - current) <= 3 || $0.key == index }
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
            endTurn()
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
    private let zoomView = ZoomableImageView()

    init(index: Int, urls: [URL?], rightToLeft: Bool) {
        self.index = index
        self.urls = urls
        self.rightToLeft = rightToLeft
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
        if urls.count > 1 {
            zoomView.loadSpread(urls, rightToLeft: rightToLeft)
        } else {
            zoomView.load(urls.first ?? nil)
            // Fast devices sharpen the pages around the one shown too.
            if SuperResolutionSpeed.isFast { zoomView.enhanceWhenReady() }
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if urls.count == 1 { zoomView.enhanceWhenReady() }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
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
    private var task: ImageTask?
    private var spreadTask: Task<Void, Never>?
    private var currentURL: URL?
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

    private static let doubleTapInterval: CFTimeInterval = 0.28

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
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
            return zoomScale > minimumZoomScale + 0.01
        }
        let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = swipeDelegate
        addGestureRecognizer(pan)
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
        task?.cancel()
        spreadTask?.cancel()
        pendingTap?.cancel()
        enhanceTask?.cancel()
    }

    // MARK: Super resolution

    private var enhanceTask: Task<Void, Never>?
    private var wantsEnhance = false
    private var enhancedURL: URL?

    /// Sharpens the page once it has loaded (Super Resolution settings).
    func enhanceWhenReady() {
        wantsEnhance = true
        startEnhance()
    }

    private func startEnhance() {
        guard wantsEnhance, enhanceTask == nil, let url = currentURL, enhancedURL != url,
              let image = imageView.image else { return }
        let settings = AppSettings.shared
        let mode = settings.superResolution
        guard mode != .off else { return }
        let kind: SuperResolutionModel = settings.superResolutionModel == .high && SuperResolutionModels.shared.isReady
            ? .high : .standard
        let key = url.absoluteString + (settings.autoCrop ? "|crop" : "")
        let target = ReaderScreen.pixels
        enhanceTask = Task { [weak self] in
            let enhanced = await SuperResolver.shared.enhance(image, key: key, target: target, mode: mode, kind: kind)
            guard let self, !Task.isCancelled else { return }
            self.enhanceTask = nil
            guard let enhanced, self.currentURL == url else { return }
            self.enhancedURL = url
            // The same picture, sharper: a slow cross-fade, no reload.
            UIView.transition(with: self.imageView, duration: 0.35,
                              options: [.transitionCrossDissolve, .allowUserInteraction]) {
                self.imageView.image = enhanced
            }
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale == minimumZoomScale {
            imageView.frame = CGRect(origin: .zero, size: bounds.size)
            contentSize = bounds.size
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
        pendingTap?.cancel()
        pendingTap = nil
        if zoomScale != minimumZoomScale {
            setZoomScale(minimumZoomScale, animated: false)
        }
    }

    func load(_ url: URL?) {
        guard url != currentURL else { return }
        task?.cancel()
        spreadTask?.cancel()
        enhanceTask?.cancel()
        enhanceTask = nil
        enhancedURL = nil
        currentURL = url
        imageView.image = nil
        resetZoom()
        hideFailure()
        guard let url else { return }
        let request = AutoCropProcessor.request(url)
        if let cached = ImagePipeline.shared.cache[request] {
            imageView.image = cached.image
            startEnhance()
            return
        }
        spinner.startAnimating()
        task = ImagePipeline.shared.loadImage(with: request) { [weak self] result in
            guard let self, self.currentURL == url else { return }
            self.spinner.stopAnimating()
            switch result {
            case let .success(response):
                UIView.transition(with: self.imageView, duration: 0.15, options: .transitionCrossDissolve) {
                    self.imageView.image = response.image
                }
                self.startEnhance()
            case .failure:
                // Not left black: the page says it failed and loads again on a tap.
                self.showFailure { [weak self] in
                    self?.currentURL = nil
                    self?.load(url)
                }
            }
        }
    }

    /// Two pages joined into one image (the first on the left, or on the
    /// right when reading right to left), zoomed as one.
    func loadSpread(_ urls: [URL?], rightToLeft: Bool) {
        let key = urls.compactMap { $0 }
        guard key.count == 2 else { load(urls.first ?? nil); return }
        task?.cancel()
        spreadTask?.cancel()
        currentURL = nil
        imageView.image = nil
        resetZoom()
        hideFailure()
        spinner.startAnimating()
        let requests = key.map(AutoCropProcessor.request)
        spreadTask = Task { [weak self] in
            async let a = ImagePipeline.shared.image(for: requests[0])
            async let b = ImagePipeline.shared.image(for: requests[1])
            let (left, right) = (try? await a, try? await b)
            guard !Task.isCancelled, let self else { return }
            guard let first = left, let second = right else {
                // One of the two failed: the spinner doesn't turn forever.
                self.showFailure { [weak self] in self?.loadSpread(urls, rightToLeft: rightToLeft) }
                return
            }
            let settings = AppSettings.shared
            let image = Self.join(rightToLeft ? second : first, rightToLeft ? first : second,
                                  gap: settings.spreadGapOn ? settings.spreadGap : 0, screen: ReaderScreen.pixels,
                                  scale: self.window?.screen.scale ?? self.traitCollection.displayScale)
            self.spinner.stopAnimating()
            UIView.transition(with: self.imageView, duration: 0.15, options: .transitionCrossDissolve) {
                self.imageView.image = image
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

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        let offsetX = max((bounds.width - contentSize.width) / 2, 0)
        let offsetY = max((bounds.height - contentSize.height) / 2, 0)
        imageView.center = CGPoint(x: contentSize.width / 2 + offsetX, y: contentSize.height / 2 + offsetY)
    }

    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        guard bounds.width > 0 else { return }
        let location = gesture.location(in: self)
        // Position on screen (the scroll view's bounds move when zoomed).
        let point = CGPoint(x: location.x - bounds.minX, y: location.y - bounds.minY)
        let fraction = CGPoint(x: point.x / bounds.width, y: point.y / max(bounds.height, 1))
        let zoomed = zoomScale > minimumZoomScale + 0.01

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
            self?.onTap?(CGPoint(x: 0.5, y: 0.5))
        }
        pendingTap = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.doubleTapInterval, execute: work)
    }

    private func toggleZoom(at point: CGPoint) {
        if zoomScale > minimumZoomScale {
            setZoomScale(minimumZoomScale, animated: true)
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

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            block()
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            // Leaving the reader (or starting to): the other screens have them again.
            for gesture in blocked { gesture.isEnabled = true }
            blocked = []
        }

        private func block() {
            guard let navigation = navigationController else { return }
            var found: [UIGestureRecognizer] = []
            if #available(iOS 26.0, *), let swipe = navigation.interactiveContentPopGestureRecognizer {
                found.append(swipe)
            }
            // The zoom transition's own closing drag: on the screens between the
            // reader and the navigation controller (not on the reader's own views).
            var view: UIView? = parent?.view
            while let current = view, current !== navigation.view.superview {
                for gesture in current.gestureRecognizers ?? [] where gesture !== navigation.interactivePopGestureRecognizer {
                    if String(describing: type(of: gesture)).localizedCaseInsensitiveContains("zoom") { found.append(gesture) }
                }
                view = current.superview
            }
            for gesture in found where gesture.isEnabled {
                gesture.isEnabled = false
                blocked.append(gesture)
            }
            Diagnostics.shared.log(category: "reader", message: "Back swipes kept from the page: " +
                                   (blocked.map { String(describing: type(of: $0)) }.joined(separator: ", ")))
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
