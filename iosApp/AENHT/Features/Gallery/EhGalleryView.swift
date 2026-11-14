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
            // Moving between folders keeps the note.
            let note = (target != nil && before != nil) ? ((try? await EhService.shared.favoriteNote(gid: gid, token: token)) ?? "") : ""
            try await EhService.shared.setFavorite(gid: gid, token: token, folder: target, note: note)
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
    @State private var editingNote = false
    @State private var note = ""
    @Environment(\.openURL) private var openURL
    @Environment(AppSettings.self) private var settings
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
                Menu {
                    if let card = model.card {
                        Button("Open in Browser", systemImage: "safari") { openURL(card.webURL) }
                    }
                    if let detail = model.detail, detail.torrentCount > 0 {
                        NavigationLink {
                            EhTorrentsView(gid: model.gid, token: model.token)
                        } label: {
                            Label("Torrents (\(Int(detail.torrentCount)))", systemImage: "arrow.down.circle")
                        }
                    }
                    if model.folder != nil, EhSession.shared.isLoggedIn {
                        Button("Favorite Note", systemImage: "note.text") {
                            Task {
                                note = (try? await EhService.shared.favoriteNote(gid: model.gid, token: model.token)) ?? ""
                                editingNote = true
                            }
                        }
                    }
                } label: {
                    Label("More", systemImage: "ellipsis")
                }
            }
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
        .alert("Favorite Note", isPresented: $editingNote) {
            TextField("Note", text: $note)
            Button("Save") {
                guard let folder = model.folder else { return }
                Task {
                    do {
                        try await EhService.shared.setFavorite(gid: model.gid, token: model.token, folder: folder, note: String(note.prefix(200)))
                    } catch {
                        alertMessage = error.localizedDescription
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
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
                if let detail = model.detail {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        TranslateLine(text: detail.japaneseTitle ?? detail.title, kind: .title)
                        Spacer(minLength: 0)
                        ratingMenu(detail)
                    }
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

    /// The rating under the title (tap: rate the gallery).
    private func ratingMenu(_ detail: EhGalleryDetail) -> some View {
        Menu {
            Section("\(Int(detail.ratingCount)) ratings") {
                ForEach((1...10).reversed(), id: \.self) { half in
                    let stars = Double(half) / 2
                    Button(stars.truncatingRemainder(dividingBy: 1) == 0 ? "★ \(Int(stars))" : String(format: "★ %.1f", stars)) {
                        Task { alertMessage = await model.rate(stars) }
                    }
                }
            }
        } label: {
            HStack(spacing: 4) {
                RatingStars(rating: model.rating, size: 11)
                Text(model.rating, format: .number.precision(.fractionLength(2)))
                    .font(.footnote.weight(.semibold).monospacedDigit())
                    .foregroundStyle(detail.ratedByUser ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
            }
            .fixedSize()
        } primaryAction: {
            if !EhSession.shared.isLoggedIn { showLogin = true }
        }
        .buttonStyle(.plain)
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
            HStack(alignment: .firstTextBaseline) {
                SectionTitle(title: "Comments")
                if !detail.comments.isEmpty || EhSession.shared.isLoggedIn {
                    NavigationLink {
                        EhCommentsView(detail: detail)
                    } label: {
                        if detail.comments.isEmpty { Text("Add a comment…") } else { Text("See All (\(detail.comments.count))") }
                    }
                    .font(.subheadline)
                    .padding(.trailing)
                    .fixedSize()
                }
            }
            if detail.comments.isEmpty {
                Text("No comments yet").font(.subheadline).foregroundStyle(.secondary).padding(.horizontal, 20)
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(detail.comments.filter { !settings.blockedCommenterSet.contains($0.author) }, id: \.id) { comment in
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
            TranslateLine(text: comment.text, kind: .comment)
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

/// All comments of an E-Hentai gallery, and a field to add one when logged in.
struct EhCommentsView: View {
    let detail: EhGalleryDetail
    var gid: Int64 { detail.gid }
    var token: String { detail.token }
    @State private var comments: [EhComment]
    @State private var draft = ""
    @State private var isPosting = false
    @State private var error: String?
    @State private var blocking: EhComment?
    @FocusState private var composing: Bool
    @Environment(AppSettings.self) private var settings

    init(detail: EhGalleryDetail) {
        self.detail = detail
        _comments = State(initialValue: detail.comments)
    }

    var body: some View {
        List {
            ForEach(comments.filter { !settings.blockedCommenterSet.contains($0.author) }, id: \.id) { comment in
                EhCommentBubble(comment: comment, lineLimit: nil)
                    .padding(.vertical, 4)
                    .contextMenu {
                        if EhSession.shared.isLoggedIn, !comment.isUploader {
                            Button("Vote Up", systemImage: "hand.thumbsup") { Task { await vote(comment, up: true) } }
                            Button("Vote Down", systemImage: "hand.thumbsdown") { Task { await vote(comment, up: false) } }
                        }
                        Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = comment.text }
                        if !comment.isUploader {
                            Button("Block \(comment.author)", systemImage: "eye.slash", role: .destructive) { blocking = comment }
                        }
                    }
            }
        }
        .confirmationDialog("Block Commenter?", isPresented: Binding(get: { blocking != nil }, set: { if !$0 { blocking = nil } }),
                            titleVisibility: .visible) {
            Button("Block", role: .destructive) {
                if let blocking { settings.blockCommenter(blocking.author) }
            }
        } message: {
            Text("Comments by this user are hidden on every gallery. Unblock in Settings › Filters.")
        }
        .listStyle(.plain)
        .overlay {
            if comments.isEmpty { ContentUnavailableView("No Comments", systemImage: "text.bubble") }
        }
        .navigationTitle("Comments")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable {
            if let fresh = try? await EhService.shared.gallery(gid: Int(gid), token: token) { comments = fresh.comments }
        }
        .safeAreaInset(edge: .bottom) {
            if EhSession.shared.isLoggedIn { composer }
        }
        .alert("Couldn't Post", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Add a comment…", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .focused($composing)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            Button {
                Task { await post() }
            } label: {
                if isPosting {
                    ProgressView().frame(width: 36, height: 36)
                } else {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 34))
                        .symbolRenderingMode(.hierarchical)
                }
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).count < 10 || isPosting)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .modifier(BarBackground())
    }

    private func vote(_ comment: EhComment, up: Bool) async {
        do {
            if let score = try await EhService.shared.voteComment(detail, commentId: comment.id, up: up),
               let i = comments.firstIndex(where: { $0.id == comment.id }) {
                let c = comments[i]
                comments[i] = EhComment(id: c.id, author: c.author, posted: c.posted, score: KotlinInt(int: Int32(score)),
                                        text: c.text, isUploader: c.isUploader)
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func post() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isPosting = true
        defer { isPosting = false }
        do {
            comments = try await EhService.shared.postComment(gid: gid, token: token, text: text)
            draft = ""
            composing = false
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// A gallery's torrents (tap: download the .torrent, made for the account).
struct EhTorrentsView: View {
    let gid: Int
    let token: String
    @State private var torrents: [EhTorrent]?
    @State private var error: String?
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            if let error {
                ContentUnavailableView("Couldn't Load", systemImage: "wifi.exclamationmark", description: Text(error))
            } else if let torrents {
                if torrents.isEmpty {
                    ContentUnavailableView("No Torrents", systemImage: "arrow.down.circle")
                } else {
                    Section {
                        ForEach(torrents, id: \.url) { t in
                            Button {
                                if let url = URL(string: t.url) { openURL(url) }
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(t.name).foregroundStyle(.primary)
                                    Text("\(t.size) · \(String(localized: "Seeds")) \(t.seeds) · \(String(localized: "Peers")) \(t.peers) · \(t.posted)")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .contextMenu {
                                Button("Copy Link", systemImage: "link") { UIPasteboard.general.string = t.url }
                            }
                        }
                    } footer: {
                        Text("Torrent links are made for your account; don't share them.")
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Torrents")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { torrents = try await EhService.shared.torrents(gid: gid, token: token) } catch { self.error = error.localizedDescription }
        }
    }
}
