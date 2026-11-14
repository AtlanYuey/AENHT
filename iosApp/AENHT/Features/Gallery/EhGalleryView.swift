import Shared
import SwiftData
import SwiftUI

/// Loads an E-Hentai gallery page and keeps its state.
@MainActor
@Observable
final class EhGalleryModel {
    let gid: Int
    let token: String
    let preview: GalleryItem?
    private(set) var detail: EhGalleryDetail?
    private(set) var error: String?
    var folder: Int?
    var rating: Double = 0
    var busy = false

    init(route: EhGalleryRoute) {
        gid = route.gid
        token = route.token
        preview = route.preview
    }

    var card: GalleryItem? {
        detail.map { GalleryItem(ehDetail: $0, preview: preview) } ?? preview
    }

    func load(context: ModelContext) async {
        error = nil
        do {
            let loaded = try await EhService.shared.gallery(gid: gid, token: token)
            let first = detail == nil
            detail = loaded
            folder = loaded.favoriteFolder.map { Int(truncating: $0) }
            rating = loaded.rating
            await EhPageDirectory.shared.remember(gid: gid, thumbnails: loaded.thumbnails)
            if first, let card {
                context.recordHistory(item: card, title: loaded.title, numPages: Int(loaded.pages))
            }
        } catch {
            if detail == nil { self.error = error.localizedDescription }
        }
        await EhSession.shared.loadFolders()
    }

    func setFolder(_ target: Int?) async -> String? {
        let before = folder
        folder = target
        busy = true
        defer { busy = false }
        do {
            try await EhService.shared.setFavorite(gid: gid, token: token, folder: target)
            if let card {
                if let target { await FavoritesStore.shared.added(card, folder: target) } else { await FavoritesStore.shared.removed(card) }
            }
            return nil
        } catch {
            folder = before
            return error.localizedDescription
        }
    }

    func rate(_ stars: Double) async -> String? {
        guard let detail else { return nil }
        do {
            rating = try await EhService.shared.rate(detail, stars: stars)
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}

struct EhGalleryView: View {
    @State private var model: EhGalleryModel
    @State private var showLogin = false
    @State private var alertMessage: String?
    @State private var confirmDeleteDownload = false
    @State private var titleExpanded = false
    @Environment(\.modelContext) private var modelContext
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(DownloadManager.self) private var downloads
    @Environment(TagStore.self) private var tagStore

    init(route: EhGalleryRoute) {
        _model = State(initialValue: EhGalleryModel(route: route))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let detail = model.detail {
                    actions(detail)
                    info(detail)
                    tags(detail)
                    previews(detail)
                    comments(detail)
                } else if let error = model.error {
                    ContentUnavailableView {
                        Label("Couldn't Load", systemImage: "wifi.exclamationmark")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Try Again") { Task { await model.load(context: modelContext) } }
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(.top, 40)
                } else {
                    ProgressView().controlSize(.large).frame(maxWidth: .infinity).padding(.top, 60)
                }
            }
            .padding(.bottom, 32)
            .background(alignment: .top) {
                Backdrop(url: model.card?.thumbURL, height: sizeClass == .regular ? 420 : 320)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let card = model.card {
                    ShareLink(item: card.webURL, subject: Text(card.title)) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .task { if model.detail == nil { await model.load(context: modelContext) } }
        .refreshable { await model.load(context: modelContext) }
        .sheet(isPresented: $showLogin) { EhLoginView() }
        .alert("Couldn't Update", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(alertMessage ?? "")
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .bottom, spacing: 18) {
            Group {
                if let card = model.card {
                    CoverView(item: card, cornerRadius: 14, targetWidth: 500)
                } else {
                    RoundedRectangle(cornerRadius: 14).fill(.fill.quaternary).frame(height: 180)
                }
            }
            .frame(width: sizeClass == .regular ? 200 : 132)
            .shadow(color: .black.opacity(0.25), radius: 14, y: 6)
            VStack(alignment: .leading, spacing: 8) {
                Text(model.detail?.title ?? model.preview?.title ?? "")
                    .font(sizeClass == .regular ? .title2.weight(.semibold) : .title3.weight(.semibold))
                    .lineLimit(titleExpanded ? nil : 5)
                    .onTapGesture { withAnimation(.smooth) { titleExpanded.toggle() } }
                    .contextMenu {
                        Button("Copy Title", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = [model.detail?.title, model.detail?.japaneseTitle].compactMap { $0 }.joined(separator: "\n")
                        }
                    }
                if let japanese = model.detail?.japaneseTitle {
                    Text(japanese).font(.subheadline).foregroundStyle(.secondary).lineLimit(titleExpanded ? nil : 2)
                }
                HStack(spacing: 8) {
                    if let category = model.card?.category {
                        Text(category.label)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(category.swiftColor, in: Capsule())
                    }
                    if let uploader = model.detail?.uploader ?? model.preview?.uploader {
                        NavigationLink(value: EhSearchRoute(query: "uploader:\"\(uploader)\"", title: uploader)) {
                            Text(uploader).font(.subheadline.weight(.semibold)).lineLimit(1)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal)
        .padding(.top, 12)
    }

    // MARK: Actions

    private func actions(_ detail: EhGalleryDetail) -> some View {
        let card = model.card ?? GalleryItem(ehDetail: detail, preview: nil)
        let lastPage = modelContext.lastReadPage(key: card.uid) ?? 0
        let record = downloads.record(uid: card.uid)
        return HStack(spacing: 12) {
            NavigationLink(value: ReaderRoute(source: ReaderSource(eh: detail, card: card), page: lastPage)) {
                Label(lastPage > 0 ? "Resume · \(lastPage + 1)" : "Read", systemImage: lastPage > 0 ? "play.fill" : "book.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .modifier(ProminentButtonStyle())
            Menu {
                ForEach(Array(EhSession.shared.folderNames.enumerated()), id: \.offset) { index, name in
                    Button {
                        Task { alertMessage = await model.setFolder(model.folder == index ? nil : index) }
                    } label: {
                        if model.folder == index { Label(name, systemImage: "checkmark") } else { Text(name) }
                    }
                }
                if model.folder != nil {
                    Divider()
                    Button("Remove from Favorites", systemImage: "trash", role: .destructive) {
                        Task { alertMessage = await model.setFolder(nil) }
                    }
                }
            } label: {
                Image(systemName: model.folder != nil ? "heart.fill" : "heart")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(model.folder.map { EhSession.folderColors[$0] == .black ? Color.pink : EhSession.folderColors[$0] } ?? .accentColor)
                    .frame(width: 44, height: 44)
            } primaryAction: {
                if !EhSession.shared.isLoggedIn { showLogin = true }
            }
            .modifier(CircleGlassStyle())
            .disabled(model.busy)
            downloadButton(detail, card: card, record: record)
        }
        .padding(.horizontal)
        .padding(.top, 22)
    }

    @ViewBuilder
    private func downloadButton(_ detail: EhGalleryDetail, card: GalleryItem, record: DownloadRecord?) -> some View {
        switch record?.state {
        case .finished:
            CircleButton(systemImage: "checkmark.circle.fill", label: "Downloaded", tint: .green) { confirmDeleteDownload = true }
                .confirmationDialog("Delete the downloaded pages?", isPresented: $confirmDeleteDownload, titleVisibility: .visible) {
                    Button("Delete Download", role: .destructive) { downloads.delete(uid: card.uid) }
                }
        case .downloading, .queued:
            CircleButton(systemImage: "pause.fill", label: "Pause Download", progress: record?.progress) { downloads.pause(uid: card.uid) }
        case .paused, .failed:
            CircleButton(systemImage: "arrow.clockwise", label: "Resume Download", progress: record?.progress) { downloads.resume(uid: card.uid) }
        case nil:
            CircleButton(systemImage: "arrow.down", label: "Download") { downloads.start(eh: detail, card: card) }
        }
    }

    // MARK: Info

    private func info(_ detail: EhGalleryDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            // The rating matters most on E-Hentai: shown large; the menu rates.
            Menu {
                ForEach((1...10).reversed(), id: \.self) { half in
                    let stars = Double(half) / 2
                    Button(stars.truncatingRemainder(dividingBy: 1) == 0 ? "★ \(Int(stars))" : "★ \(stars, specifier: "%.1f")") {
                        Task { alertMessage = await model.rate(stars) }
                    }
                }
            } label: {
                HStack(spacing: 10) {
                    Text(model.rating, format: .number.precision(.fractionLength(2)))
                        .font(.title2.bold())
                        .foregroundStyle(.primary)
                    RatingStars(rating: model.rating, size: 17)
                    Text("\(Int(detail.ratingCount)) ratings").font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    if detail.ratedByUser {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint)
                    }
                }
            } primaryAction: {
                if !EhSession.shared.isLoggedIn { showLogin = true }
            }
            .buttonStyle(.plain)
            Divider()
            HStack {
                infoCell("Pages", "\(detail.pages)")
                infoCell("Favorites", Int(detail.favoritedCount).formatted(.number.notation(.compactName)))
                infoCell("Posted", String(detail.posted.split(separator: " ").first ?? ""))
                infoCell("Size", detail.fileSize ?? "-")
            }
            Divider()
        }
        .padding(.horizontal)
        .padding(.top, 22)
    }

    private func infoCell(_ title: LocalizedStringKey, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(title).font(.caption.weight(.medium)).foregroundStyle(.secondary).lineLimit(1)
            Text(value).font(.subheadline.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: Tags

    private func tags(_ detail: EhGalleryDetail) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(Bridge.shared.tagNamespaces(detail: detail), id: \.self) { namespace in
                VStack(alignment: .leading, spacing: 8) {
                    Text(namespace.capitalized)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                    FlowLayout(spacing: 8) {
                        ForEach(Bridge.shared.tags(detail: detail, namespace: namespace), id: \.full) { tag in
                            let name = tagStore.displayName(tag.name, type: namespace)
                            NavigationLink(value: EhSearchRoute(query: tag.query, title: name)) {
                                TagChip(name: name)
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = tag.full }
                            }
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
        .padding(.top, 18)
    }

    // MARK: Pages

    private func previews(_ detail: EhGalleryDetail) -> some View {
        let card = model.card ?? GalleryItem(ehDetail: detail, preview: nil)
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                SectionTitle(title: "Pages")
                if detail.pages > 1 {
                    NavigationLink("See All", value: EhPagesRoute(gid: model.gid, token: model.token, pages: Int(detail.pages), card: card))
                        .padding(.trailing)
                }
            }
            ScrollView(.horizontal) {
                LazyHStack(spacing: 10) {
                    ForEach(detail.thumbnails, id: \.page) { thumb in
                        NavigationLink(value: ReaderRoute(source: ReaderSource(eh: detail, card: card), page: Int(thumb.page) - 1)) {
                            VStack(spacing: 6) {
                                let ratio = thumb.width > 0 && thumb.height > 0 ? CGFloat(thumb.width) / CGFloat(thumb.height) : 0.7
                                EhThumbnailView(thumb: thumb)
                                    .frame(width: 160 * min(max(ratio, 0.5), 1.5), height: 160)
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                Text("\(thumb.page)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
            .scrollIndicators(.hidden)
        }
    }

    // MARK: Comments

    private func comments(_ detail: EhGalleryDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(title: "Comments")
            if detail.comments.isEmpty {
                Text("No comments yet").font(.subheadline).foregroundStyle(.secondary).padding(.horizontal, 20)
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(detail.comments, id: \.id) { comment in
                            EhCommentBubble(comment: comment, lineLimit: 5)
                                .frame(width: 300, alignment: .topLeading)
                                .padding(14)
                                .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                    }
                    .padding(.horizontal)
                }
                .scrollIndicators(.hidden)
            }
        }
    }
}

struct EhCommentBubble: View {
    let comment: EhComment
    var lineLimit: Int? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(comment.author).font(.subheadline.weight(.semibold)).lineLimit(1)
                if comment.isUploader {
                    Text("Uploader").font(.caption2).foregroundStyle(.secondary)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(.fill.tertiary, in: Capsule())
                }
                Spacer(minLength: 0)
                if let score = comment.score.map({ Int(truncating: $0) }) {
                    Text(score > 0 ? "+\(score)" : "\(score)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(score >= 0 ? .green : .red)
                }
            }
            Text(comment.posted).font(.caption).foregroundStyle(.secondary)
            Text(comment.text).font(.subheadline).lineLimit(lineLimit)
        }
    }
}

/// Every page's thumbnail of an E-Hentai gallery; more load while scrolling.
struct EhPagesView: View {
    let route: EhPagesRoute
    @State private var thumbs: [EhThumbnail] = []
    @State private var nextPage = 0
    @State private var loading = false
    @State private var detail: EhGalleryDetail?

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 14) {
                ForEach(Array(thumbs.enumerated()), id: \.element.page) { index, thumb in
                    Group {
                        if let detail {
                            NavigationLink(value: ReaderRoute(source: ReaderSource(eh: detail, card: route.card), page: Int(thumb.page) - 1)) {
                                cell(thumb)
                            }
                            .buttonStyle(.plain)
                        } else {
                            cell(thumb)
                        }
                    }
                    .onAppear { if index >= thumbs.count - 6 { Task { await loadMore() } } }
                }
            }
            .padding()
        }
        .navigationTitle("Pages")
        .task {
            if detail == nil {
                detail = try? await EhService.shared.gallery(gid: route.gid, token: route.token)
                thumbs = detail?.thumbnails ?? []
                nextPage = 1
            }
        }
    }

    private func cell(_ thumb: EhThumbnail) -> some View {
        VStack(spacing: 4) {
            EhThumbnailView(thumb: thumb)
                .frame(height: 130)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            Text("\(thumb.page)").font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func loadMore() async {
        guard let detail, !loading, thumbs.count < route.pages, nextPage < Int(detail.thumbnailPageCount) else { return }
        loading = true
        defer { loading = false }
        if let more = try? await EhService.shared.thumbnails(gid: route.gid, token: route.token, page: nextPage) {
            let known = Set(thumbs.map(\.page))
            thumbs += more.filter { !known.contains($0.page) }
            await EhPageDirectory.shared.remember(gid: route.gid, thumbnails: more)
            nextPage += 1
        }
    }
}

/// An E-Hentai list opened from a gallery (a tag or an uploader).
struct EhListView: View {
    let route: EhSearchRoute
    @State private var feed: GalleryFeed

    init(route: EhSearchRoute) {
        self.route = route
        let query = route.query
        _feed = State(initialValue: GalleryFeed.eh { Bridge.shared.search(query: query, categoriesShown: 0) })
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                GalleryCollection(items: feed.items) { item in
                    Task { await feed.loadMoreIfNeeded(after: item) }
                }
                .padding(.top, 8)
                FeedPlaceholder(feed: feed)
                FeedFooter(feed: feed)
            }
        }
        .navigationTitle(route.title)
        .refreshable { await feed.reload() }
        .task { if !feed.hasLoaded { await feed.reload() } }
    }
}
