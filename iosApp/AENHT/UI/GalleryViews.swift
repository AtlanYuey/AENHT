import Shared
import SwiftUI
import UIKit

extension EnvironmentValues {
    /// Namespace for the zoom transition from a gallery card to its page.
    @Entry var galleryNamespace: Namespace.ID? = nil
    /// Drawn over a gallery's cover in cards and rows (download or reading progress).
    @Entry var galleryCoverBadge: ((GalleryItem) -> AnyView)? = nil
    /// More items for a gallery's context menu (Delete in History and Downloads).
    @Entry var galleryMenuItems: ((GalleryItem) -> AnyView)? = nil
    /// Lists that mix both sites mark each cover with NH / EH (top right).
    @Entry var showsSiteBadge: Bool = false
}

/// Registers the gallery / search destinations of a navigation stack and
/// the zoom transition between cards and gallery pages.
struct GalleryDestinations: ViewModifier {
    @Namespace private var namespace

    func body(content: Content) -> some View {
        content
            .environment(\.galleryNamespace, namespace)
            .navigationDestination(for: GalleryRoute.self) { route in
                GalleryDetailView(route: route)
                    .environment(\.galleryNamespace, namespace)
                    .navigationTransition(.zoom(sourceID: "nh:\(route.id)", in: namespace))
            }
            .navigationDestination(for: SearchRoute.self) { route in
                SearchResultsView(route: route)
                    .environment(\.galleryNamespace, namespace)
            }
            .navigationDestination(for: ReaderRoute.self) { route in
                ReaderDestination(route: route, namespace: namespace)
            }
            .navigationDestination(for: PagesRoute.self) { route in
                PagesView(detail: route.detail)
                    .environment(\.galleryNamespace, namespace)
            }
            .navigationDestination(for: EhGalleryRoute.self) { route in
                EhGalleryView(route: route)
                    .environment(\.galleryNamespace, namespace)
                    .navigationTransition(.zoom(sourceID: "eh:\(route.gid)", in: namespace))
            }
            .navigationDestination(for: EhSearchRoute.self) { route in
                EhListView(route: route)
                    .environment(\.galleryNamespace, namespace)
            }
            .navigationDestination(for: EhPagesRoute.self) { route in
                EhPagesView(route: route)
                    .environment(\.galleryNamespace, namespace)
            }
            .navigationDestination(for: DownloadsRoute.self) { _ in
                DownloadsView()
                    .environment(\.galleryNamespace, namespace)
            }
    }
}

extension View {
    func galleryDestinations() -> some View {
        modifier(GalleryDestinations())
    }

    /// Marks this view as the source of a gallery zoom transition.
    @ViewBuilder
    func galleryTransitionSource(_ id: String, _ namespace: Namespace.ID?) -> some View {
        if let namespace {
            matchedTransitionSource(id: id, in: namespace)
        } else {
            self
        }
    }
}

/// Language badge shown on covers.
struct LanguageBadge: View {
    let text: String
    var tint: Color? = nil

    init(language: GalleryLanguage) {
        text = language.badge
    }

    init(text: String, tint: Color? = nil) {
        self.text = text
        self.tint = tint
    }

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(tint.map { AnyShapeStyle($0.opacity(0.85)) } ?? AnyShapeStyle(.black.opacity(0.45)), in: Capsule())
    }
}

/// Opens a gallery of either site.
struct GalleryLinkButton<Label: View>: View {
    let item: GalleryItem
    @ViewBuilder let label: () -> Label

    var body: some View {
        if item.site == .ehentai {
            NavigationLink(value: EhGalleryRoute(gid: item.id, token: item.token ?? "", preview: item), label: label)
        } else {
            NavigationLink(value: GalleryRoute(id: item.id, preview: item), label: label)
        }
    }
}

/// 0–5 stars in half steps (E-Hentai ratings).
struct RatingStars: View {
    let rating: Double
    var size: CGFloat = 12

    var body: some View {
        HStack(spacing: 1) {
            ForEach(0..<5, id: \.self) { i in
                let fill = min(max(rating - Double(i), 0), 1)
                Image(systemName: fill >= 0.75 ? "star.fill" : fill >= 0.25 ? "star.leadinghalf.filled" : "star")
                    .font(.system(size: size))
                    .foregroundStyle(.orange)
            }
        }
        .accessibilityLabel(Text(String(format: "%.1f", rating)))
    }
}

/// A gallery cover with rounded corners and the language badge.
struct CoverView: View {
    let item: GalleryItem
    var aspectRatio: CGFloat? = nil
    var cornerRadius: CGFloat = 12
    var targetWidth: CGFloat = 360

    @Environment(AppSettings.self) private var settings
    @Environment(TagStore.self) private var tagStore
    @Environment(\.showsSiteBadge) private var showsSiteBadge

    /// The badge's color: the category on both sites (nhentai's category
    /// tag, in E-Hentai's category colors).
    private var categoryColor: Color? {
        if let category = item.category { return category.swiftColor }
        guard item.site == .nhentai else { return nil }
        for id in item.tagIDs {
            if let tag = tagStore.tag(id), tag.type == "category", let category = EhCategory.companion.fromLabel(label: tag.name) {
                return CategoryInfo(category).swiftColor
            }
        }
        return nil
    }

    var body: some View {
        Color.clear
            .aspectRatio(aspectRatio ?? item.aspectRatio, contentMode: .fit)
            .overlay {
                RemoteImage(url: item.thumbURL, targetWidth: targetWidth, blur: settings.blurCovers) {
                    // Without the network, a downloaded gallery shows its first page.
                    DownloadManager.shared.localURL(item: item, page: 0)
                }
            }
            .overlay(alignment: .topLeading) {
                if let badge = item.languageBadge {
                    LanguageBadge(text: badge, tint: categoryColor).padding(6)
                } else if let category = item.category {
                    Circle().fill(category.swiftColor).frame(width: 10, height: 10).padding(8)
                }
            }
            .overlay(alignment: .topTrailing) {
                if showsSiteBadge {
                    LanguageBadge(text: item.site.badge).padding(6)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(.primary.opacity(0.08), lineWidth: 0.5)
            }
    }
}

/// Card used by the grid / waterfall styles.
struct GalleryCard: View {
    let item: GalleryItem
    let style: GalleryListStyle

    @Environment(\.galleryNamespace) private var namespace
    @Environment(\.galleryCoverBadge) private var badge
    @Environment(AppSettings.self) private var settings

    var body: some View {
        GalleryLinkButton(item: item) {
            VStack(alignment: .leading, spacing: 6) {
                switch style {
                case .grid:
                    CoverView(item: item, aspectRatio: 3 / 4)
                        .overlay { badge?(item) }
                        .galleryTransitionSource(item.transitionKey, namespace)
                    title.frame(maxWidth: .infinity, minHeight: 34, alignment: .topLeading)
                case .compact:
                    CoverView(item: item)
                        .overlay(alignment: .bottom) { compactOverlay }
                        .overlay { badge?(item) }
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .galleryTransitionSource(item.transitionKey, namespace)
                default:
                    CoverView(item: item)
                        .overlay { badge?(item) }
                        .galleryTransitionSource(item.transitionKey, namespace)
                    title
                    CardFooter(item: item, style: .plain)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            GalleryContextMenu(item: item)
        } preview: {
            CoverView(item: item, cornerRadius: 0, targetWidth: 600)
                .frame(width: 280)
        }
    }

    private var title: some View {
        Text(item.prettyTitle)
            .font(.footnote.weight(.medium))
            .foregroundStyle(.primary)
            .lineLimit(2)
            .multilineTextAlignment(.leading)
            .privacyText()
    }

    /// Tags and title over the bottom of the cover.
    private var compactOverlay: some View {
        VStack(alignment: .leading, spacing: 6) {
            CardFooter(item: item, style: .overlay)
            Text(item.prettyTitle)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .privacyText()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.top, 36)
        .padding(.bottom, 10)
        .background(
            LinearGradient(colors: [.clear, .black.opacity(0.55), .black.opacity(0.8)],
                           startPoint: .top, endPoint: .bottom)
        )
    }
}

/// Under a card: its tags (when shown) and, on the right, the rating.
struct CardFooter: View {
    let item: GalleryItem
    let style: CardTagLine.Style

    @Environment(AppSettings.self) private var settings

    var body: some View {
        let rating = item.rating ?? 0
        if settings.showTags || rating > 0 {
            HStack(spacing: 6) {
                if settings.showTags {
                    CardTags(item: item, style: style)
                }
                Spacer(minLength: 0)
                if rating > 0 {
                    RatingBadge(rating: rating, overlay: style == .overlay)
                        .fixedSize()
                        .layoutPriority(1)
                }
            }
        }
    }
}

/// One star filled as far as the rating goes (of 5), and the rating.
struct RatingBadge: View {
    let rating: Double
    var overlay = false
    var size: CGFloat = 11

    var body: some View {
        HStack(spacing: 3) {
            ZStack {
                Image(systemName: "star.fill").foregroundStyle(.orange.opacity(0.28))
                Image(systemName: "star.fill").foregroundStyle(.orange)
                    .mask(alignment: .leading) {
                        GeometryReader { g in
                            Rectangle().frame(width: g.size.width * min(max(rating / 5, 0), 1))
                        }
                    }
            }
            .font(.system(size: size, weight: .semibold))
            Text(String(format: "%.2f", rating))
                .font(.system(size: size, weight: .semibold).monospacedDigit())
                .foregroundStyle(overlay ? AnyShapeStyle(.white) : AnyShapeStyle(.secondary))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(String(format: "%.2f", rating)))
    }
}

/// The card's tags: nhentai tag ids or E-Hentai tag names.
struct CardTags: View {
    let item: GalleryItem
    let style: CardTagLine.Style

    @Environment(TagStore.self) private var tagStore

    var body: some View {
        Group {
            if item.site == .nhentai {
                CardTagLine(ids: item.tagIDs, style: style)
            } else {
                // Translated like the gallery page's tags (Translate Tags).
                CardTagChips(names: item.ehTags.prefix(8).map { name in
                    tagStore.displayName(name, type: tagStore.ehNamespaces(name).first ?? "tag")
                }, style: style)
            }
        }
        .privacyText()
    }
}

/// One line of chips from names.
struct CardTagChips: View {
    let names: [String]
    let style: CardTagLine.Style

    var body: some View {
        if !names.isEmpty {
            FittingRow(spacing: 5) {
                ForEach(names, id: \.self) { name in
                    Text(name)
                        .font(.caption2.weight(.medium))
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .foregroundStyle(style == .overlay ? AnyShapeStyle(.white) : AnyShapeStyle(.secondary))
                        .background(
                            style == .overlay ? AnyShapeStyle(.white.opacity(0.2)) : AnyShapeStyle(.fill.tertiary),
                            in: Capsule()
                        )
                }
            }
        }
    }
}

/// One line of tag chips; chips that don't fit are left out.
struct CardTagLine: View {
    enum Style { case plain, overlay }

    let ids: [Int]
    let style: Style

    @Environment(TagStore.self) private var tagStore

    var body: some View {
        let tags = tagStore.cardTags(for: ids)
        if !tags.isEmpty {
            FittingRow(spacing: 5) {
                ForEach(tags, id: \.self) { tag in
                    Text(tagStore.displayName(tag.name, type: tag.type))
                        .font(.caption2.weight(.medium))
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .foregroundStyle(style == .overlay ? AnyShapeStyle(.white) : AnyShapeStyle(.secondary))
                        .background(
                            style == .overlay ? AnyShapeStyle(.white.opacity(0.2)) : AnyShapeStyle(.fill.tertiary),
                            in: Capsule()
                        )
                }
            }
        }
    }
}

/// Lays children out in one row and hides the ones that don't fit.
struct FittingRow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var width: CGFloat = 0
        var height: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let next = width == 0 ? size.width : width + spacing + size.width
            if next > maxWidth { break }
            width = next
            height = max(height, size.height)
        }
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var full = false
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if full || x + size.width > bounds.maxX + 0.5 {
                full = true
                subview.place(at: CGPoint(x: -10_000, y: -10_000), proposal: .zero)
                continue
            }
            subview.place(at: CGPoint(x: x, y: bounds.minY), proposal: ProposedViewSize(size))
            x += size.width + spacing
        }
    }
}

/// Row used by the list style.
struct GalleryRow: View {
    let item: GalleryItem
    var subtitle: String? = nil

    @Environment(\.galleryNamespace) private var namespace
    @Environment(\.galleryCoverBadge) private var badge

    var body: some View {
        GalleryLinkButton(item: item) {
            HStack(alignment: .top, spacing: 14) {
                CoverView(item: item, aspectRatio: 0.72, cornerRadius: 10, targetWidth: 200)
                    .overlay { badge?(item) }
                    .frame(width: 84)
                    .galleryTransitionSource(item.transitionKey, namespace)
                VStack(alignment: .leading, spacing: 6) {
                    Text(item.prettyTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(3)
                        .multilineTextAlignment(.leading)
                        .privacyText()
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if item.numPages > 0 {
                        Label("\(item.numPages) pages", systemImage: "doc.on.doc")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if item.site == .ehentai {
                        HStack(spacing: 8) {
                            if let category = item.category {
                                Text(category.label)
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 2)
                                    .background(category.swiftColor, in: Capsule())
                            }
                            if let rating = item.rating, rating > 0 {
                                RatingStars(rating: rating)
                            }
                        }
                        if let uploader = item.uploader {
                            Text(uploader).font(.footnote).foregroundStyle(.tertiary).lineLimit(1).privacyText()
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { GalleryContextMenu(item: item) }
    }
}

struct GalleryContextMenu: View {
    let item: GalleryItem

    @Environment(\.galleryMenuItems) private var extraItems

    var body: some View {
        if let extraItems {
            extraItems(item)
            Divider()
        }
        ShareLink(item: item.webURL) {
            Label("Share", systemImage: "square.and.arrow.up")
        }
        Button {
            UIPasteboard.general.url = item.webURL
        } label: {
            Label("Copy Link", systemImage: "link")
        }
    }
}

/// Galleries laid out in the user's list style; the column count follows
/// the available width and the card size setting for the orientation.
struct GalleryCollection: View {
    let items: [GalleryItem]
    var onItemAppear: (GalleryItem) -> Void = { _ in }

    @Environment(AppSettings.self) private var settings
    @State private var width: CGFloat = 0
    @State private var isLandscape = false

    private let spacing: CGFloat = 12

    var body: some View {
        Group {
            switch settings.listStyle {
            case .list:
                LazyVStack(spacing: 0) {
                    ForEach(items, id: \.uid) { item in
                        GalleryRow(item: item)
                            .padding(.horizontal)
                            .onAppear { onItemAppear(item) }
                        Divider().padding(.leading, 114)
                    }
                }
            case .grid:
                LazyVGrid(columns: gridColumns, alignment: .leading, spacing: 18) {
                    ForEach(items, id: \.uid) { item in
                        GalleryCard(item: item, style: .grid)
                            .onAppear { onItemAppear(item) }
                    }
                }
                .padding(.horizontal)
            case .waterfall, .compact:
                Masonry(items: items, columns: columnCount, spacing: spacing,
                        style: settings.listStyle, onItemAppear: onItemAppear)
                    .padding(.horizontal)
            }
        }
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
            width = size.width
        }
        .background {
            GeometryReader { proxy in
                Color.clear.onAppear { updateOrientation(proxy) }
                    .onChange(of: proxy.size) { updateOrientation(proxy) }
            }
        }
    }

    private func updateOrientation(_ proxy: GeometryProxy) {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
        if let scene {
            isLandscape = scene.effectiveGeometry.interfaceOrientation.isLandscape
        } else {
            isLandscape = proxy.size.width > proxy.size.height
        }
    }

    /// The columns chosen in Card Size for this device and orientation; a
    /// narrow pane (Split View, Slide Over) shows fewer rather than cards
    /// narrower than 90 pt.
    private var columnCount: Int {
        let available = max(0, width - 32)
        guard available > 0 else { return settings.columns(isLandscape: isLandscape) }
        let fit = max(1, Int((available + spacing) / (90 + spacing)))
        return min(settings.columns(isLandscape: isLandscape), fit)
    }

    private var gridColumns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: .top), count: columnCount)
    }
}

/// Waterfall layout: each card goes to the currently shortest column.
private struct Masonry: View {
    let items: [GalleryItem]
    let columns: Int
    let spacing: CGFloat
    let style: GalleryListStyle
    let onItemAppear: (GalleryItem) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: spacing) {
            ForEach(Array(distributed.enumerated()), id: \.offset) { _, column in
                LazyVStack(spacing: style == .compact ? spacing : 16) {
                    ForEach(column, id: \.uid) { item in
                        GalleryCard(item: item, style: style)
                            .onAppear { onItemAppear(item) }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .top)
            }
        }
    }

    private var distributed: [[GalleryItem]] {
        let count = max(1, columns)
        var result = Array(repeating: [GalleryItem](), count: count)
        var heights = Array(repeating: CGFloat(0), count: count)
        let titleHeight: CGFloat = style == .compact ? 0 : 0.28
        for item in items {
            let index = heights.indices.min { heights[$0] < heights[$1] } ?? 0
            result[index].append(item)
            heights[index] += 1 / max(item.aspectRatio, 0.3) + titleHeight
        }
        return result
    }
}

/// Lays out children in rows, wrapping like text (used for tags).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8
    /// Room kept free at the end of the first line (for a button there).
    var firstLineTrailing: CGFloat = 0

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var widest: CGFloat = 0
        var firstLine = true
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let limit = firstLine ? maxWidth - firstLineTrailing : maxWidth
            if x > 0, x + size.width > limit {
                y += lineHeight + lineSpacing
                x = 0
                lineHeight = 0
                firstLine = false
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var lineHeight: CGFloat = 0
        var firstLine = true
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            let limit = firstLine ? bounds.maxX - firstLineTrailing : bounds.maxX
            if x > bounds.minX, x + size.width > limit {
                y += lineHeight + lineSpacing
                x = bounds.minX
                lineHeight = 0
                firstLine = false
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// Footer of an infinite list: spinner while loading, error with retry.
struct FeedFooter: View {
    let feed: GalleryFeed

    var body: some View {
        Group {
            if feed.isLoading, !feed.items.isEmpty {
                ProgressView()
            } else if let error = feed.error, !feed.items.isEmpty {
                VStack(spacing: 8) {
                    Text(error).font(.footnote).foregroundStyle(.secondary)
                    Button("Retry") { Task { await feed.loadMore() } }
                        .buttonStyle(.bordered)
                }
            } else if feed.skippedHidden, !feed.items.isEmpty {
                Button("Load More") { Task { await feed.loadMore() } }
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }
}

/// Full-screen state of an empty / failed / loading feed.
struct FeedPlaceholder: View {
    let feed: GalleryFeed
    var emptyTitle: LocalizedStringKey = "No Galleries"
    var emptyImage = "photo.on.rectangle"

    var body: some View {
        if !feed.hasLoaded || (feed.isLoading && feed.items.isEmpty) {
            ProgressView()
                .controlSize(.large)
                .frame(maxWidth: .infinity, minHeight: 300)
        } else if let error = feed.error, feed.items.isEmpty {
            ContentUnavailableView {
                Label("Couldn't Load", systemImage: "wifi.exclamationmark")
            } description: {
                Text(error)
            } actions: {
                Button("Try Again") { Task { await feed.reload() } }
                    .buttonStyle(.borderedProminent)
            }
        } else if feed.items.isEmpty, feed.skippedHidden {
            ContentUnavailableView {
                Label(emptyTitle, systemImage: emptyImage)
            } description: {
                Text("Everything on these pages is hidden by your filters.")
            } actions: {
                Button("Load More") { Task { await feed.loadMore() } }
                    .buttonStyle(.borderedProminent)
            }
        } else if feed.items.isEmpty {
            ContentUnavailableView(emptyTitle, systemImage: emptyImage)
        }
    }
}
