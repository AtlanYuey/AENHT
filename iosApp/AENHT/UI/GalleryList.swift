import SwiftUI
import UIKit

// MARK: - The list's contents

/// A part of a gallery list, top to bottom: a SwiftUI view across the width
/// (a header, a section title, a footer), or gallery cards in the user's
/// list style.
struct GalleryBlock {
    enum Content {
        case view(AnyView)
        case cards([GalleryItem])
    }

    let id: String
    let content: Content

    static func view<V: View>(_ id: String, @ViewBuilder _ view: () -> V) -> GalleryBlock {
        GalleryBlock(id: id, content: .view(AnyView(view())))
    }

    static func cards(_ id: String, _ items: [GalleryItem]) -> GalleryBlock {
        GalleryBlock(id: id, content: .cards(items))
    }
}

extension EnvironmentValues {
    /// Opens a gallery from a card in a `GalleryList` (its cards are UIKit
    /// cells, out of reach of the navigation stack's links).
    @Entry var openGallery: ((GalleryItem) -> Void)? = nil
}

/// What a card of a `GalleryList` opened.
enum OpenedGallery: Hashable {
    case nh(GalleryRoute)
    case eh(EhGalleryRoute)

    init(_ item: GalleryItem) {
        if item.site == .ehentai {
            self = .eh(EhGalleryRoute(gid: item.id, token: item.token ?? "", preview: item))
        } else {
            self = .nh(GalleryRoute(id: item.id, preview: item))
        }
    }
}

/// A scrolling list of galleries (Home, Favorites, History, search
/// results, Downloads) in a UICollectionView: every card's place is worked
/// out from its cover's shape, so turning the device lays the cards out
/// again in the same moment (in the rotation's animation) and the list
/// stays on the card that was at the top, without going back to the top
/// first or showing an empty frame.
struct GalleryList: View {
    let blocks: [GalleryBlock]
    /// A card is about to show (to load the next page).
    var onItemAppear: (GalleryItem) -> Void = { _ in }
    /// Pull to refresh (none without it).
    var refresh: (() async -> Void)? = nil
    /// The tab whose first page this is: its tab button takes the list to the top, back, or (twice) refreshes.
    var tab: AppTab? = nil
    var tabTapEnabled = true
    /// Its tab was chosen (from another one), while the list is near its top.
    var onTabOpened: (() async -> Void)? = nil
    /// Scrolling down hides the tab bar (Appearance › Hide Tab Bar When Scrolling).
    var hidesBars = true
    /// How far the list is scrolled and where the first block ends (Home's header docking in the bar).
    var onScroll: ((_ offset: CGFloat, _ firstBlockBottom: CGFloat) -> Void)? = nil

    @Environment(AppSettings.self) private var settings
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openRoute) private var openRoute
    @State private var chrome = AppChrome.shared

    var body: some View {
        GalleryListRepresentable(
            list: self,
            layout: GalleryListLayoutKey(
                style: settings.listStyle,
                showTags: settings.showTags,
                columns: [settings.columns(isLandscape: false), settings.columns(isLandscape: true)],
                dynamicType: dynamicTypeSize
            ),
            tabTap: tab == nil ? nil : chrome.tabTap,
            tabOpened: tab == nil ? nil : chrome.tabOpened
        )
        // Opened in the stack's path, like the links on the gallery's page (RoutedNavigationStack).
        .environment(\.openGallery) { item in openRoute?(OpenedGallery(item)) }
        .ignoresSafeArea(.container)
        .background {
            if let tab { TabNavigationFinder(tab: tab).frame(width: 0, height: 0) }
        }
    }
}

/// What the cards' sizes depend on besides the cards: when it changes, they are measured again.
struct GalleryListLayoutKey: Equatable {
    var style: GalleryListStyle
    var showTags: Bool
    var columns: [Int]
    var dynamicType: DynamicTypeSize
}

private struct GalleryListRepresentable: UIViewControllerRepresentable {
    let list: GalleryList
    let layout: GalleryListLayoutKey
    let tabTap: TabTap?
    let tabOpened: TabTap?

    func makeUIViewController(context: Context) -> GalleryListController {
        GalleryListController()
    }

    func updateUIViewController(_ controller: GalleryListController, context: Context) {
        controller.update(list: list, layout: layout, tabTap: tabTap, tabOpened: tabOpened, environment: context.environment)
    }

    static func dismantleUIViewController(_ controller: GalleryListController, coordinator: ()) {
        controller.disappeared()
    }
}

// MARK: - The collection view

final class GalleryListController: UIViewController, UICollectionViewDelegate {
    private var collectionView: UICollectionView!
    private var dataSource: UICollectionViewDiffableDataSource<String, String>!

    /// The SwiftUI environment the cells' views get (settings, the zoom's namespace, badges…).
    private var environment = EnvironmentValues()
    private var blocks: [GalleryBlock] = []
    /// The card of each card cell, and the view of each view cell, by cell id.
    private var items: [String: GalleryItem] = [:]
    private var views: [String: AnyView] = [:]
    private var layoutKey: GalleryListLayoutKey?
    private var onItemAppear: (GalleryItem) -> Void = { _ in }
    private var refresh: (() async -> Void)?
    private var onScroll: ((CGFloat, CGFloat) -> Void)?
    private var hidesBars = true
    /// The tab whose first page this is (UIViewController's own `tab` is UIKit's).
    private var appTab: AppTab?
    private var tabTapEnabled = true
    private var lastTabTap: TabTap?
    private var lastTabOpened: TabTap?
    /// What of the environment the cells' views show that doesn't reach them
    /// by itself: when it changes (the app comes back and its privacy blur
    /// ends, the theme changes), every cell is drawn again.
    private var cellEnvironment: CellEnvironment?

    private struct CellEnvironment: Equatable {
        var privacy: BackgroundPrivacy
        var colorScheme: ColorScheme
        /// Choosing started or ended (Select): every card shows it, or stops.
        var choosing: ObjectIdentifier?
    }

    private let metrics = CardMetrics()
    private let bars = ScrollBarsHiding()

    override func loadView() {
        let layout = UICollectionViewCompositionalLayout { [weak self] index, environment in
            self?.section(at: index, environment: environment)
        }
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collectionView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        collectionView.backgroundColor = .clear
        // Cells are drawn again when the width changes (the visible ones): none kept ready off screen.
        collectionView.isPrefetchingEnabled = false
        collectionView.delegate = self
        collectionView.allowsSelection = false
        collectionView.alwaysBounceVertical = true
        // Only the scrolling direction: the sides' safe area is left to the sections (see section(at:)).
        collectionView.contentInsetAdjustmentBehavior = .scrollableAxes
        collectionView.keyboardDismissMode = .onDrag
        if #available(iOS 26.0, *) {
            // The soft edge under the bars, like the app's SwiftUI lists (SoftScrollEdges), not the flat one.
            collectionView.topEdgeEffect.style = .soft
            collectionView.bottomEdgeEffect.style = .soft
        }
        // In a view of its own: the width change is handled after this view's
        // layout, not inside the collection view's own.
        let root = UIView()
        root.backgroundColor = .clear
        collectionView.frame = root.bounds
        root.addSubview(collectionView)
        view = root

        let cell = UICollectionView.CellRegistration<UICollectionViewCell, String> { [weak self] cell, indexPath, id in
            self?.configure(cell, id: id, at: indexPath)
        }
        dataSource = UICollectionViewDiffableDataSource(collectionView: collectionView) { collectionView, indexPath, id in
            collectionView.dequeueConfiguredReusableCell(using: cell, for: indexPath, item: id)
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        claimScrollEdges(always: true)
    }

    /// The navigation bar's scroll edge (and the iPhone's tab bar) follow this
    /// list: when it appears, or when the list that had them went away (Home's search).
    private func claimScrollEdges(always: Bool) {
        guard view.window != nil else { return }
        var controller: UIViewController? = self
        while let current = controller, !(current.parent is UINavigationController) { controller = current.parent }
        guard let page = controller else { return }
        if always || page.contentScrollView(for: .top) == nil { page.setContentScrollView(collectionView, for: .top) }
        if always || page.contentScrollView(for: .bottom) == nil { page.setContentScrollView(collectionView, for: .bottom) }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        disappeared()
    }

    func disappeared() {
        if hidesBars { bars.reset() }
    }

    // MARK: Updates from SwiftUI

    func update(list: GalleryList, layout: GalleryListLayoutKey, tabTap: TabTap?, tabOpened: TabTap?, environment: EnvironmentValues) {
        loadViewIfNeeded()
        self.environment = environment
        onItemAppear = list.onItemAppear
        onScroll = list.onScroll
        hidesBars = list.hidesBars
        appTab = list.tab
        tabTapEnabled = list.tabTapEnabled
        setRefresh(list.refresh)

        let relayout = layoutKey != nil && layoutKey != layout
        if relayout { metrics.clear() }
        layoutKey = layout
        let cells = CellEnvironment(privacy: environment.privacyMode, colorScheme: environment.colorScheme,
                                    choosing: environment.gallerySelection.map(ObjectIdentifier.init))
        let redraw = cellEnvironment != nil && cellEnvironment != cells
        cellEnvironment = cells
        apply(list.blocks, relayout: relayout, redraw: redraw)
        claimScrollEdges(always: false)

        if let tabTap {
            if let last = lastTabTap, last != tabTap, tabTap.tab == appTab { tabTapped() }
            lastTabTap = tabTap
        }
        if let tabOpened {
            // Chosen again: reloaded when near the top (scrolled further down, the place stays).
            if let last = lastTabOpened, last != tabOpened, tabOpened.tab == appTab, let opened = list.onTabOpened {
                let offset = collectionView.contentOffset.y + collectionView.adjustedContentInset.top
                if offset < collectionView.bounds.height * 2 { Task { @MainActor in await opened() } }
            }
            lastTabOpened = tabOpened
        }
    }

    private func setRefresh(_ action: (() async -> Void)?) {
        refresh = action
        if action == nil {
            collectionView.refreshControl = nil
        } else if collectionView.refreshControl == nil {
            let control = UIRefreshControl()
            control.addAction(UIAction { [weak self] action in
                guard let self, let refresh = self.refresh, let control = action.sender as? UIRefreshControl else { return }
                Task { @MainActor in
                    await refresh()
                    control.endRefreshing()
                }
            }, for: .valueChanged)
            collectionView.refreshControl = control
        }
    }

    /// Cell ids: "v|block" for a view, "c|block|gallery" for a card.
    private static func viewID(_ block: String) -> String { "v|\(block)" }
    private static func cardID(_ block: String, _ uid: String) -> String { "c|\(block)|\(uid)" }

    /// [relayout]: the cards' sizes changed; [redraw]: every cell is drawn again (same sizes).
    private func apply(_ newBlocks: [GalleryBlock], relayout: Bool, redraw: Bool) {
        var snapshot = NSDiffableDataSourceSnapshot<String, String>()
        var newItems: [String: GalleryItem] = [:]
        var newViews: [String: AnyView] = [:]
        var changed: [String] = []
        var cardsChanged = false
        for block in newBlocks {
            snapshot.appendSections([block.id])
            switch block.content {
            case let .view(view):
                let id = Self.viewID(block.id)
                snapshot.appendItems([id], toSection: block.id)
                newViews[id] = view
                changed.append(id)
            case let .cards(cards):
                var seen = Set<String>()
                var ids: [String] = []
                for card in cards where seen.insert(card.uid).inserted {
                    let id = Self.cardID(block.id, card.uid)
                    ids.append(id)
                    if let old = items[id], old != card {
                        changed.append(id)
                        cardsChanged = true
                    }
                    newItems[id] = card
                }
                snapshot.appendItems(ids, toSection: block.id)
            }
        }
        var current = dataSource.snapshot()
        let sameIDs = snapshot.sectionIdentifiers == current.sectionIdentifiers
            && snapshot.itemIdentifiers == current.itemIdentifiers
        blocks = newBlocks
        items = newItems
        views = newViews
        if sameIDs {
            // The same cells: their views (and changed cards; all of them when their size changed) are shown again.
            let again = relayout || redraw ? current.itemIdentifiers : changed
            if !again.isEmpty { current.reconfigureItems(again) }
            dataSource.apply(current, animatingDifferences: false)
        } else {
            let before = Set(current.itemIdentifiers)
            let again = relayout || redraw ? snapshot.itemIdentifiers : changed
            let existing = again.filter { before.contains($0) }
            if !existing.isEmpty { snapshot.reconfigureItems(existing) }
            dataSource.apply(snapshot, animatingDifferences: false)
        }
        if relayout || cardsChanged { collectionView.collectionViewLayout.invalidateLayout() }
    }

    // MARK: Cells

    private func configure(_ cell: UICollectionViewCell, id: String, at indexPath: IndexPath) {
        let environment = environment
        if let view = views[id] {
            cell.contentConfiguration = UIHostingConfiguration {
                view.modifier(ListCellEnvironment(values: environment))
            }
            .margins(.all, 0)
            return
        }
        guard let item = items[id], let key = layoutKey else { return }
        let width = cardWidth
        switch key.style {
        case .list:
            cell.contentConfiguration = UIHostingConfiguration {
                VStack(spacing: 0) {
                    GalleryRow(item: item).padding(.horizontal)
                    Divider().padding(.leading, 114)
                }
                .modifier(ListCellEnvironment(values: environment))
            }
            .margins(.all, 0)
        case .grid, .waterfall, .compact:
            let size = metrics.cardSize(item, style: key.style, width: width, environment: environment, traits: traitCollection)
            cell.contentConfiguration = UIHostingConfiguration {
                // The cell's size is the layout's: the card fills it.
                GalleryCard(item: item, style: key.style, fixed: size.fixed)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .modifier(ListCellEnvironment(values: environment))
            }
            .margins(.all, 0)
        }
    }

    // MARK: Layout

    private static let sideInset: CGFloat = 16
    private static let columnSpacing: CGFloat = 12

    /// The columns for this width: the Card Size setting for the orientation,
    /// fewer in a narrow pane (Split View, Slide Over) rather than cards
    /// narrower than 90 pt.
    private func columnCount(width: CGFloat) -> Int {
        // The screen's sides, not the window's (in Split View the window can be narrow either way);
        // already turned when the layout is worked out during a rotation.
        let screen = view.window?.windowScene?.screen.bounds.size ?? view.bounds.size
        let landscape = screen.width > screen.height
        let wanted = AppSettings.shared.columns(isLandscape: landscape)
        let available = max(0, width - 2 * Self.sideInset)
        guard available > 0 else { return wanted }
        let fit = max(1, Int((available + Self.columnSpacing) / (90 + Self.columnSpacing)))
        return max(1, min(wanted, fit))
    }

    /// The width of one card, as the layout last worked it out (the cells are drawn at it).
    private var cardWidth: CGFloat = 100

    /// The width of one card in a list [width] wide (the safe area's sides already left out).
    private func cardWidth(forListWidth width: CGFloat) -> CGFloat {
        let columns = CGFloat(columnCount(width: width))
        let available = width - 2 * Self.sideInset - (columns - 1) * Self.columnSpacing
        return max(1, floor(available / columns * 2) / 2)
    }

    private func section(at index: Int, environment layoutEnvironment: NSCollectionLayoutEnvironment) -> NSCollectionLayoutSection? {
        guard index < blocks.count, let key = layoutKey else { return nil }
        // Within the safe area at the sides (a phone's notch held sideways), like SwiftUI's lists.
        let safe = collectionView.safeAreaInsets
        let width = layoutEnvironment.container.effectiveContentSize.width - safe.left - safe.right
        let section: NSCollectionLayoutSection
        switch blocks[index].content {
        case .view:
            let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .estimated(120))
            let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [NSCollectionLayoutItem(layoutSize: size)])
            section = NSCollectionLayoutSection(group: group)
        case .cards:
            let cards = dataSource.snapshot().itemIdentifiers(inSection: blocks[index].id).compactMap { items[$0] }
            if key.style != .list { cardWidth = cardWidth(forListWidth: width) }
            if cards.isEmpty {
                // An empty section still needs a group.
                let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .absolute(1))
                section = NSCollectionLayoutSection(group: .vertical(layoutSize: size, subitems: [NSCollectionLayoutItem(layoutSize: size)]))
            } else {
                switch key.style {
                case .list:
                    let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .estimated(130))
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [NSCollectionLayoutItem(layoutSize: size)])
                    section = NSCollectionLayoutSection(group: group)
                case .grid:
                    section = gridSection(cards, width: width)
                case .waterfall, .compact:
                    section = waterfallSection(cards, style: key.style, width: width)
                }
            }
        }
        section.contentInsetsReference = .safeArea
        return section
    }

    private func gridSection(_ cards: [GalleryItem], width: CGFloat) -> NSCollectionLayoutSection {
        let columns = columnCount(width: width)
        let height = metrics.cardSize(cards[0], style: .grid, width: cardWidth, environment: environment, traits: traitCollection).height
        let item = NSCollectionLayoutItem(layoutSize: NSCollectionLayoutSize(widthDimension: .absolute(cardWidth), heightDimension: .absolute(height)))
        let group = NSCollectionLayoutGroup.horizontal(
            layoutSize: NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .absolute(height)),
            repeatingSubitem: item, count: columns
        )
        group.interItemSpacing = .fixed(Self.columnSpacing)
        let section = NSCollectionLayoutSection(group: group)
        section.interGroupSpacing = 18
        section.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: Self.sideInset, bottom: 0, trailing: Self.sideInset)
        return section
    }

    /// Each card goes to the column that is shortest so far, at the size its cover's shape gives it.
    private func waterfallSection(_ cards: [GalleryItem], style: GalleryListStyle, width: CGFloat) -> NSCollectionLayoutSection {
        let columns = columnCount(width: width)
        let spacing: CGFloat = style == .compact ? Self.columnSpacing : 16
        var heights = Array(repeating: CGFloat(0), count: columns)
        var frames: [CGRect] = []
        frames.reserveCapacity(cards.count)
        for card in cards {
            let column = heights.indices.min { heights[$0] < heights[$1] } ?? 0
            let height = metrics.cardSize(card, style: style, width: cardWidth, environment: environment, traits: traitCollection).height
            let x = CGFloat(column) * (cardWidth + Self.columnSpacing)
            let y = heights[column] == 0 ? 0 : heights[column] + spacing
            frames.append(CGRect(x: x, y: y, width: cardWidth, height: height))
            heights[column] = y + height
        }
        let total = heights.max() ?? 0
        let group = NSCollectionLayoutGroup.custom(
            layoutSize: NSCollectionLayoutSize(widthDimension: .fractionalWidth(1), heightDimension: .absolute(max(1, total)))
        ) { _ in
            frames.map { NSCollectionLayoutGroupCustomItem(frame: $0) }
        }
        let section = NSCollectionLayoutSection(group: group)
        section.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: Self.sideInset, bottom: 0, trailing: Self.sideInset)
        return section
    }

    // MARK: Keeping the place when the width changes

    /// The cell at the top of the list and how far above the top it starts.
    private struct Anchor {
        let id: String
        let offset: CGFloat
    }

    private var anchor: Anchor?
    private var atTop = true
    private var laidOutWidth: CGFloat = 0

    private var topOffset: CGFloat { collectionView.contentOffset.y + collectionView.adjustedContentInset.top }

    /// The cell first on screen: of the cards, the one that starts highest among those still showing.
    private func recordAnchor() {
        atTop = topOffset <= 1
        let top = topOffset
        var best: (id: String, minY: CGFloat)?
        for indexPath in collectionView.indexPathsForVisibleItems {
            guard let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame,
                  frame.maxY > top + 1,
                  let id = dataSource.itemIdentifier(for: indexPath) else { continue }
            if best == nil || frame.minY < best!.minY { best = (id, frame.minY) }
        }
        anchor = best.map { Anchor(id: $0.id, offset: $0.minY - top) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let width = collectionView.bounds.width
        let previous = laidOutWidth
        // Set first: laying out again below comes back here.
        laidOutWidth = width
        guard previous > 0, abs(width - previous) > 0.5 else { return }
        // Laid out again at once for the new width, then back to the same cell (in
        // the same pass: during a rotation, inside its animation).
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.layoutIfNeeded()
        // The cards on screen drawn again at their new size (their titles may take
        // other lines); the others are drawn when they come on screen.
        for indexPath in collectionView.indexPathsForVisibleItems {
            guard let cell = collectionView.cellForItem(at: indexPath),
                  let id = dataSource.itemIdentifier(for: indexPath) else { continue }
            configure(cell, id: id, at: indexPath)
        }
        collectionView.layoutIfNeeded()
        restoreAnchor()
    }

    /// Back to the cell recorded in [anchor] (or the top).
    private func restoreAnchor() {
        let inset = collectionView.adjustedContentInset
        if atTop {
            collectionView.contentOffset.y = -inset.top
            return
        }
        guard let anchor, let indexPath = dataSource.indexPath(for: anchor.id),
              let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame else { return }
        let maximum = max(-inset.top, collectionView.contentSize.height + inset.bottom - collectionView.bounds.height)
        let target = min(max(frame.minY - anchor.offset - inset.top, -inset.top), maximum)
        if abs(collectionView.contentOffset.y - target) > 0.5 { collectionView.contentOffset.y = target }
    }

    /// The place as the turn starts (the freshest, also when a page was just
    /// closed and its animation is still going), and once more when it has
    /// ended: whatever settled after the new layout (that animation ending,
    /// the bars) doesn't move the list off it.
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        if abs(collectionView.bounds.width - laidOutWidth) < 0.5, view.window != nil { recordAnchor() }
        let kept = anchor
        let top = atTop
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            guard let self else { return }
            self.anchor = kept
            self.atTop = top
            self.collectionView.layoutIfNeeded()
            self.restoreAnchor()
        }
    }

    // MARK: Scrolling

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        // The place is recorded only in a settled layout (not while the width is changing).
        if abs(collectionView.bounds.width - laidOutWidth) < 0.5 { recordAnchor() }
        let inset = collectionView.adjustedContentInset
        if hidesBars {
            bars.update(offset: topOffset, topInset: inset.top,
                        maxOffset: collectionView.contentSize.height + inset.top + inset.bottom - collectionView.bounds.height)
        }
        if let onScroll {
            let firstBottom = collectionView.numberOfSections > 0 && collectionView.numberOfItems(inSection: 0) > 0
                ? collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: 0))?.frame.maxY ?? .infinity
                : .infinity
            onScroll(topOffset, firstBottom)
        }
    }

    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let id = dataSource.itemIdentifier(for: indexPath), let item = items[id] else { return }
        onItemAppear(item)
    }

    // MARK: The tab button

    /// Where the list was when the tab button took it to the top.
    private var savedOffset: CGFloat?
    private var pendingTap: Task<Void, Never>?
    private var lastTopTap = Date.distantPast
    private static let doubleTap: TimeInterval = 0.3

    private func scroll(to offset: CGFloat) {
        let inset = collectionView.adjustedContentInset
        collectionView.setContentOffset(CGPoint(x: 0, y: offset - inset.top), animated: true)
    }

    /// Away from the top, the list goes to the top; at the top, back to where
    /// it was; tapped twice quickly, it refreshes.
    private func tabTapped() {
        guard tabTapEnabled, view.window != nil else { return }
        if pendingTap != nil || Date().timeIntervalSince(lastTopTap) < Self.doubleTap {
            pendingTap?.cancel()
            pendingTap = nil
            lastTopTap = .distantPast
            savedOffset = nil
            scroll(to: 0)
            if let refresh { Task { @MainActor in await refresh() } }
            return
        }
        if topOffset > 1 {
            savedOffset = topOffset
            lastTopTap = Date()
            scroll(to: 0)
            return
        }
        pendingTap = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(Self.doubleTap))
            guard let self, !Task.isCancelled else { return }
            self.pendingTap = nil
            guard let back = self.savedOffset else { return }
            self.savedOffset = nil
            self.scroll(to: back)
        }
    }
}

// MARK: - Card sizes

/// The size of each card, worked out instead of measured on screen: the
/// cover's shape, then the title (one or two lines) and the line of tags
/// and rating, whose heights are measured once (they depend only on the
/// text size). The card is drawn at exactly this size (`GalleryCard.fixed`).
@MainActor
final class CardMetrics {
    struct Size {
        let height: CGFloat
        let fixed: GalleryCard.Fixed
    }

    private var titleHeights: [Int: CGFloat] = [:]
    private var footerHeight: CGFloat?
    private struct TitleKey: Hashable {
        let uid: String
        let text: String
        let width: CGFloat
        let fontSize: CGFloat
    }
    private var lines: [TitleKey: Int] = [:]

    func clear() {
        titleHeights = [:]
        footerHeight = nil
        lines = [:]
    }

    func cardSize(_ item: GalleryItem, style: GalleryListStyle, width: CGFloat,
                  environment: EnvironmentValues, traits: UITraitCollection) -> Size {
        let cover = width / max(item.aspectRatio, 0.05)
        switch style {
        case .compact:
            return Size(height: ceil(cover), fixed: GalleryCard.Fixed(titleLines: 2, titleHeight: 0, footerHeight: nil))
        case .grid:
            let title = max(34, titleHeight(2, environment))
            return Size(height: ceil(width * 4 / 3 + 6 + title),
                        fixed: GalleryCard.Fixed(titleLines: 2, titleHeight: title, footerHeight: nil))
        case .waterfall, .list:
            let count = titleLines(item, width: width, traits: traits)
            let title = titleHeight(count, environment)
            let showsFooter = AppSettings.shared.showTags || (item.rating ?? 0) > 0
            let footer = showsFooter ? measuredFooter(environment) : nil
            let height = cover + 6 + title + (footer.map { 6 + $0 } ?? 0)
            return Size(height: ceil(height), fixed: GalleryCard.Fixed(titleLines: count, titleHeight: title, footerHeight: footer))
        }
    }

    /// How many lines (one or two) the card's title takes at [width].
    private func titleLines(_ item: GalleryItem, width: CGFloat, traits: UITraitCollection) -> Int {
        let size = UIFont.preferredFont(forTextStyle: .footnote, compatibleWith: traits).pointSize
        let key = TitleKey(uid: item.uid, text: item.prettyTitle, width: width, fontSize: size)
        if let known = lines[key] { return known }
        let font = UIFont.systemFont(ofSize: size, weight: .medium)
        let text = item.prettyTitle as NSString
        let height = text.boundingRect(with: CGSize(width: width, height: .greatestFiniteMagnitude),
                                       options: [.usesLineFragmentOrigin, .usesFontLeading],
                                       attributes: [.font: font], context: nil).height
        let count = min(2, max(1, Int((height / font.lineHeight).rounded())))
        // Split View can visit many widths; keep measurements bounded over a long session.
        if lines.count >= 4096 { lines.removeAll(keepingCapacity: true) }
        lines[key] = count
        return count
    }

    /// The height of a title of [count] lines, as SwiftUI draws it.
    private func titleHeight(_ count: Int, _ environment: EnvironmentValues) -> CGFloat {
        if let known = titleHeights[count] { return known }
        let sample = Array(repeating: "Ag", count: count).joined(separator: "\n")
        let height = Self.measure(
            Text(sample).font(.footnote.weight(.medium)).lineLimit(count),
            environment
        )
        titleHeights[count] = height
        return height
    }

    /// The height of the line of tags and rating.
    private func measuredFooter(_ environment: EnvironmentValues) -> CGFloat {
        if let footerHeight { return footerHeight }
        let chip = Self.measure(
            Text("tag").font(.caption2.weight(.medium)).padding(.horizontal, 7).padding(.vertical, 3),
            environment
        )
        let rating = Self.measure(RatingBadge(rating: 4.5), environment)
        let height = max(chip, rating)
        footerHeight = height
        return height
    }

    private static func measure<V: View>(_ view: V, _ environment: EnvironmentValues) -> CGFloat {
        let host = UIHostingController(rootView: view.environment(\.dynamicTypeSize, environment.dynamicTypeSize))
        return ceil(host.sizeThatFits(in: CGSize(width: 1000, height: CGFloat.greatestFiniteMagnitude)).height)
    }
}

/// What a cell's SwiftUI takes from the list's environment: these values
/// only. A cell is hosted on its own, and giving it the list's whole
/// environment (`\.self`, another host's) broke the system's own use of it:
/// a long press's menu crashed the app.
private struct ListCellEnvironment: ViewModifier {
    let values: EnvironmentValues

    func body(content: Content) -> some View {
        content
            .environment(AppSettings.shared)
            .environment(UserSession.shared)
            .environment(DownloadManager.shared)
            .environment(TagStore.shared)
            .environment(EhSession.shared)
            .environment(\.modelContext, values.modelContext)
            .environment(\.privacyMode, values.privacyMode)
            .environment(\.galleryNamespace, values.galleryNamespace)
            .environment(\.galleryCoverBadge, values.galleryCoverBadge)
            .environment(\.galleryMenuItems, values.galleryMenuItems)
            .environment(\.showsSiteBadge, values.showsSiteBadge)
            .environment(\.gallerySelection, values.gallerySelection)
            .environment(\.gallerySelect, values.gallerySelect)
            .environment(\.openRoute, values.openRoute)
            .environment(\.openGallery, values.openGallery)
            .tint(AppSettings.shared.accent.color)
    }
}
