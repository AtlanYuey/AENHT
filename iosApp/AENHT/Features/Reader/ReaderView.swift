import Nuke
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
    /// The page was changed with the controls, which then stay.
    @State private var turnFromPanel = false
    @State private var isLandscape = false
    @Environment(\.privacyMode) private var privacy
    @State private var askPage = false
    @State private var pageText = ""
    @State private var notice: String?

    @Environment(AppSettings.self) private var settings
    @Environment(\.modelContext) private var modelContext

    @State private var prefetcher = ImagePrefetcher(
        pipeline: .shared, destination: .memoryCache, maxConcurrentRequestCount: 3
    )

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
        return Spreads(pageCount: Int32(pageCount), cover: settings.doublePageCover, widePages: wide)
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
            .blur(radius: privacy == .blurImages ? 40 : 0)
            .grayscale(settings.readerGrayscale ? 1 : 0)
            .modifier(InvertIf(enabled: settings.readerInvert))
            .overlay {
                // The reader's own brightness: a dark layer over the pages.
                Color.black.opacity(min(0.8, 1 - settings.readerBrightness)).allowsHitTesting(false)
            }
            .background(Color.black)
            .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { isLandscape = $0 }
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
            .onChange(of: sliderEditing) { scheduleHide() }
            .onChange(of: showSettings) { scheduleHide() }
            .onChange(of: page) { _, new in
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
                UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
                prefetch(around: page)
                recordPage()
                scheduleHide()
            }
            .onDisappear {
                hider.cancel()
                UIApplication.shared.isIdleTimerDisabled = false
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
            )) { fraction in
                handleTap(at: fraction)
            }
            .id("\(settings.readMode.rawValue)-\(spreads == nil ? 1 : 2)-\(settings.doublePageCover)")
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

    private func handleTap(at fraction: CGFloat) {
        guard ReaderTapZones.turnsPage(at: fraction) else {
            chromeVisible.toggle()
            return
        }
        // Swapped tap zones: the left edge goes forward.
        go(step(forward: (fraction < 0.5 ? isRTL : !isRTL) != settings.swapTapZones))
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
        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
        notice = String(localized: "Saved to Photos.")
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

    private func prefetch(around index: Int) {
        let upper = min(pageCount, index + 1 + settings.preloadPages)
        let lower = max(0, index - 2)
        guard lower < upper else { return }
        let urls = source.pages[lower..<upper].compactMap(\.url).filter { !$0.isFileURL }
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
            if settings.readMode == .leftToRight || settings.readMode == .rightToLeft {
                Picker("Double Page", selection: $settings.doublePage) {
                    ForEach(DoublePage.allCases) { Text($0.title).tag($0) }
                }
                if settings.doublePage != .off {
                    Toggle("Cover Page Alone", isOn: $settings.doublePageCover)
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
        Section {
            Toggle("Keep Screen On", isOn: $settings.keepScreenOn)
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
            Toggle("Tap Edges to Turn Pages", isOn: $settings.tapToTurnPages)
            Toggle("Swap Tap Zones", isOn: $settings.swapTapZones)
            Toggle("Fade Page Turns", isOn: Binding(get: { !settings.animatePageTurns }, set: { settings.animatePageTurns = !$0 }))
        } footer: {
            Text("With fading page turns, pages don't slide: the next one fades in where you are, easier on the eyes when turning quickly.")
        }
    }
}

// MARK: - Tap zones

/// Which taps turn the page: the outer 30 % on each side, when enabled.
enum ReaderTapZones {
    static let edge: CGFloat = 0.3

    static func turnsPage(at fraction: CGFloat) -> Bool {
        AppSettings.shared.tapToTurnPages && (fraction < edge || fraction > 1 - edge)
    }
}

// MARK: - Paged reader (UIPageViewController)

/// How the paged reader shows another page.
enum PageTurn {
    /// The old page slides away as the new one slides in.
    case slide
    /// The pages change in place, the old one fading out: no motion.
    case fade
    /// The page shows at once.
    case instant
}

/// One page at a time, left-to-right, right-to-left or top-to-bottom.
/// UIPageViewController keeps the neighbouring pages alive, so turning a
/// page shows an already decoded image instead of a blank one.
struct PagedReader: UIViewControllerRepresentable {
    /// One or two pages (double page) per turn.
    let units: [[ReaderPage]]
    let mode: ReadMode
    @Binding var page: Int
    let onTap: (CGFloat) -> Void

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
        controller.dataSource = context.coordinator
        controller.delegate = context.coordinator
        if let first = context.coordinator.controller(for: page) {
            controller.setViewControllers([first], direction: .forward, animated: false)
        }
        return controller
    }

    func updateUIViewController(_ controller: UIPageViewController, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        guard let current = (controller.viewControllers?.first as? PageController)?.index,
              current != page
        else { return }
        // A turn of one page slides, or fades without the animation; a jump
        // (the slider) shows the page at once.
        let turn: PageTurn = abs(page - current) != 1 ? .instant
            : AppSettings.shared.animatePageTurns ? .slide : .fade
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

        init(_ parent: PagedReader) {
            self.parent = parent
        }

        func controller(for index: Int) -> PageController? {
            guard parent.units.indices.contains(index) else { return nil }
            if let cached = cache[index] { return cached }
            let controller = PageController(index: index, urls: parent.units[index].map(\.url), rightToLeft: parent.mode == .rightToLeft)
            controller.onTap = { [weak self] fraction in self?.parent.onTap(fraction) }
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
    var onTap: ((CGFloat) -> Void)?
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
        zoomView.onTap = { [weak self] fraction in self?.onTap?(fraction) }
        if urls.count > 1 {
            zoomView.loadSpread(urls, rightToLeft: rightToLeft)
        } else {
            zoomView.load(urls.first ?? nil)
        }
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
    private var task: ImageTask?
    private var spreadTask: Task<Void, Never>?
    private var currentURL: URL?
    private var pendingTap: DispatchWorkItem?
    private var lastTapTime: CFTimeInterval = 0
    private var lastTapPoint: CGPoint = .zero
    var onTap: ((CGFloat) -> Void)?

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

        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleTap(_:))))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        task?.cancel()
        spreadTask?.cancel()
        pendingTap?.cancel()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale == minimumZoomScale {
            imageView.frame = CGRect(origin: .zero, size: bounds.size)
            contentSize = bounds.size
        }
        spinner.center = CGPoint(x: bounds.midX, y: bounds.midY)
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
        currentURL = url
        imageView.image = nil
        resetZoom()
        guard let url else { return }
        let request = AutoCropProcessor.request(url)
        if let cached = ImagePipeline.shared.cache[request] {
            imageView.image = cached.image
            return
        }
        spinner.startAnimating()
        task = ImagePipeline.shared.loadImage(with: request) { [weak self] result in
            guard let self, self.currentURL == url else { return }
            self.spinner.stopAnimating()
            if case let .success(response) = result {
                UIView.transition(with: self.imageView, duration: 0.15, options: .transitionCrossDissolve) {
                    self.imageView.image = response.image
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
        spinner.startAnimating()
        let requests = key.map(AutoCropProcessor.request)
        spreadTask = Task { [weak self] in
            async let a = ImagePipeline.shared.image(for: requests[0])
            async let b = ImagePipeline.shared.image(for: requests[1])
            guard let first = try? await a, let second = try? await b, !Task.isCancelled, let self else { return }
            let image = Self.join(rightToLeft ? second : first, rightToLeft ? first : second)
            self.spinner.stopAnimating()
            UIView.transition(with: self.imageView, duration: 0.15, options: .transitionCrossDissolve) {
                self.imageView.image = image
            }
        }
    }

    private static func join(_ left: UIImage, _ right: UIImage) -> UIImage {
        let height = max(left.size.height, right.size.height)
        let leftWidth = left.size.width * height / max(1, left.size.height)
        let rightWidth = right.size.width * height / max(1, right.size.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // Keeps the joined image a sensible size for memory.
        let scale = min(1, 4096 / max(1, leftWidth + rightWidth))
        let size = CGSize(width: (leftWidth + rightWidth) * scale, height: height * scale)
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            left.draw(in: CGRect(x: 0, y: 0, width: leftWidth * scale, height: size.height))
            right.draw(in: CGRect(x: leftWidth * scale, y: 0, width: rightWidth * scale, height: size.height))
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
        let fraction = point.x / bounds.width
        let zoomed = zoomScale > minimumZoomScale + 0.01

        if !zoomed, ReaderTapZones.turnsPage(at: fraction) {
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
            self?.onTap?(0.5)
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
