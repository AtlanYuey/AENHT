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
    /// Other versions of the gallery (translations, the original), found by its title.
    private(set) var versions: [GalleryItem] = []
    /// Galleries like it (E-Hentai has no list of its own): same artist, parody, tags.
    private(set) var similar: [GalleryItem] = []

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
        await loadRelated()
    }

    private func search(_ query: String) async -> [GalleryItem] {
        (try? await EhService.shared.list(Bridge.shared.search(query: query, categoriesShown: 0), cursor: nil))?.items ?? []
    }

    /// Its other versions (same title once the bracketed parts are taken
    /// away), then galleries like it, a few searches at most.
    private func loadRelated() async {
        guard let detail, versions.isEmpty, similar.isEmpty else { return }
        let titles = [detail.title, detail.japaneseTitle].compactMap { $0 }
        var found: [GalleryItem] = []
        func add(_ items: [GalleryItem], check: Bool) {
            for item in items where item.id != gid && !found.contains(where: { $0.id == item.id }) {
                if !check || GalleryVersions.shared.isVersion(titles: titles, candidate: item.title) { found.append(item) }
            }
        }
        let text = GalleryVersions.shared.searchText(title: detail.title)
        if text.count >= 3 { add(await search("\"\(text)\""), check: true) }
        // The Japanese title, as a phrase: translations usually keep it.
        if let japanese = detail.japaneseTitle {
            let jp = GalleryVersions.shared.searchText(title: japanese)
            if jp.count >= 4, jp != text { add(await search("\"\(jp)\""), check: false) }
        }
        versions = Array(found.prefix(20))

        var like: [GalleryItem] = []
        for query in EhSimilar.shared.queries(detail: detail) where like.count < 12 {
            for item in await search(query) where item.id != gid
                && !versions.contains(where: { $0.id == item.id }) && !like.contains(where: { $0.id == item.id }) {
                like.append(item)
            }
        }
        similar = Array(like.prefix(20))
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
    /// The title's translation (Translate), shown in place of the title.
    @State private var titleTranslation: Translation?
    @State private var showsOriginalTitle = false

    private var shownTitle: String {
        if let titleTranslation, !showsOriginalTitle { return titleTranslation.text }
        return model.detail?.title ?? model.preview?.title ?? ""
    }
    @State private var editingNote = false
    @State private var note = ""
    @Environment(\.openURL) private var openURL
    @Environment(AppSettings.self) private var settings
    @Environment(\.modelContext) private var modelContext
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(DownloadManager.self) private var downloads
    @Environment(TagStore.self) private var tagStore
    @Environment(\.galleryNamespace) private var namespace
    /// The page in the middle of the row of pages (the one being read).
    @State private var centeredPage: Int?
    /// Where the row was when its title took it to the first page.
    @State private var savedPage: Int?

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
                    // Its translations and other versions, then galleries like it.
                    if !model.versions.isEmpty {
                        SectionTitle(title: "Other Versions", subtitle: String(localized: "Translations and other editions of this gallery"))
                        PopularRow(items: model.versions)
                    }
                    if !model.similar.isEmpty {
                        SectionTitle(title: "More Like This")
                        PopularRow(items: model.similar)
                    }
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
            // Room under the comments, clear of the tab bar.
            .padding(.bottom, 120)
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
                    if let detail = model.detail, EhSession.shared.isLoggedIn {
                        if detail.archiverKey != nil {
                            NavigationLink {
                                EhArchiveView(detail: detail)
                            } label: {
                                Label("Download Archive", systemImage: "doc.zipper")
                            }
                        }
                        NavigationLink {
                            EhTagVoteView(detail: detail)
                        } label: {
                            Label("Vote on Tags", systemImage: "tag")
                        }
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
                // Translated, the translation takes the title's place; a tap
                // switches between it and the original.
                Text(shownTitle)
                    .font(sizeClass == .regular ? .title2.weight(.semibold) : .title3.weight(.semibold))
                    .lineLimit(titleExpanded ? nil : 5)
                    .privacyText()
                    .contentTransition(.opacity)
                    .onTapGesture {
                        withAnimation(.smooth) {
                            if titleTranslation != nil { showsOriginalTitle.toggle() } else { titleExpanded.toggle() }
                        }
                    }
                    .contextMenu {
                        Button("Copy Title", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = [model.detail?.title, model.detail?.japaneseTitle].compactMap { $0 }.joined(separator: "\n")
                        }
                    }
                if let japanese = model.detail?.japaneseTitle {
                    Text(japanese).font(.subheadline).foregroundStyle(.secondary).lineLimit(titleExpanded ? nil : 2).privacyText()
                }
                if let detail = model.detail {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        TranslateLine(text: detail.japaneseTitle ?? detail.title, kind: .title, output: $titleTranslation)
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
                    .foregroundStyle(model.folder.map { EhSession.folderColors[$0] == .black ? Color.pink : EhSession.folderColors[$0] } ?? Color.accentColor)
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
        // The same strip as nhentai's gallery page.
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 0) {
                InfoCell(title: "Pages") { Text("\(detail.pages)") }
                Divider().frame(height: 34)
                InfoCell(title: "Favorites") { FavoritesCount(count: Int(detail.favoritedCount)) }
                Divider().frame(height: 34)
                InfoCell(title: "Posted") { Text(String(detail.posted.split(separator: " ").first ?? "")) }
                Divider().frame(height: 34)
                InfoCell(title: "Size") { Text(detail.fileSize ?? "-") }
            }
            .padding(.vertical, 12)
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

    // MARK: Tags

    /// The tags by namespace, laid out like nhentai's (one line each, which
    /// opens to show them all).
    private func tags(_ detail: EhGalleryDetail) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(Bridge.shared.tagNamespaces(detail: detail), id: \.self) { namespace in
                VStack(alignment: .leading, spacing: 8) {
                    Text(EhNamespace.title(namespace))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                    TagRow(items: Bridge.shared.tags(detail: detail, namespace: namespace).map { tag in
                        let name = tagStore.displayName(tag.name, type: namespace)
                        return TagRowItem(id: tag.full, name: name, route: EhSearchRoute(query: tag.query, title: name), copy: tag.full)
                    })
                }
            }
        }
        .padding(.top, 18)
    }

    // MARK: Pages

    private func previews(_ detail: EhGalleryDetail) -> some View {
        let card = model.card ?? GalleryItem(ehDetail: detail, preview: nil)
        return VStack(alignment: .leading, spacing: 0) {
            // Tapped: the row goes to the first page; again: back to where it was.
            SectionHeader(title: "Pages", onTitleTap: {
                withAnimation(.smooth) {
                    if let saved = savedPage {
                        savedPage = nil
                        centeredPage = saved
                    } else {
                        savedPage = centeredPage ?? 0
                        centeredPage = 0
                    }
                }
            }) {
                if detail.pages > 1 {
                    NavigationLink("See All", value: EhPagesRoute(gid: model.gid, token: model.token, pages: Int(detail.pages), card: card))
                }
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(detail.thumbnails.map(IndexedThumb.init)) { item in
                        let thumb = item.thumb
                        NavigationLink(value: ReaderRoute(source: ReaderSource(eh: detail, card: card), page: item.id)) {
                            VStack(spacing: 6) {
                                let ratio = thumb.width > 0 && thumb.height > 0 ? CGFloat(thumb.width) / CGFloat(thumb.height) : 0.7
                                // The same size as nhentai's row of pages.
                                let height: CGFloat = sizeClass == .regular ? 220 : 160
                                EhThumbnailView(thumb: thumb)
                                    .frame(width: height * min(max(ratio, 0.5), 1.5), height: height)
                                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                                    .readerThumbSource(gallery: Int(detail.gid), page: item.id, namespace)
                                Text("\(thumb.page)").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
            .contentMargins(.horizontal, 16, for: .scrollContent)
            .followsReader(gallery: Int(detail.gid), position: $centeredPage)
            .onScrollPhaseChange { _, phase in
                if phase == .interacting { savedPage = nil }
            }
        }
    }

    // MARK: Comments

    private func comments(_ detail: EhGalleryDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Comments") {
                if !detail.comments.isEmpty || EhSession.shared.isLoggedIn {
                    NavigationLink {
                        EhCommentsView(detail: detail)
                    } label: {
                        if detail.comments.isEmpty { Text("Add a comment…") } else { Text("See All (\(detail.comments.count))") }
                    }
                }
            }
            if detail.comments.isEmpty {
                Text("No comments yet").font(.subheadline).foregroundStyle(.secondary).padding(.horizontal)
            } else {
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(detail.comments.filter {
                            !settings.blockedCommenterSet.contains($0.author) && !Filters.shared.hidesComment(author: $0.author, text: $0.text)
                        }, id: \.id) { comment in
                            EhCommentBubble(comment: comment, lineLimit: 5)
                                .frame(width: 290, alignment: .topLeading)
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

/// The names of E-Hentai's tag namespaces, in the app's language.
enum EhNamespace {
    static func title(_ namespace: String) -> String {
        switch namespace {
        case "language": String(localized: "Languages")
        case "parody": String(localized: "Parodies")
        case "character": String(localized: "Characters")
        case "group": String(localized: "Groups")
        case "artist": String(localized: "Artists")
        case "cosplayer": String(localized: "Cosplayers")
        case "male": String(localized: "Male")
        case "female": String(localized: "Female")
        case "mixed": String(localized: "Mixed")
        case "other": String(localized: "Other")
        case "location": String(localized: "Locations")
        case "reclass": String(localized: "Reclassified")
        case "temp": String(localized: "Temporary")
        default: namespace.capitalized
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
            Text(comment.text).font(.subheadline).lineLimit(lineLimit).privacyText()
            TranslateLine(text: comment.text, kind: .comment)
        }
    }
}

/// Every page's thumbnail of an E-Hentai gallery; more load while scrolling.
/// A thumbnail with its page index (0-based), as a row's id.
struct IndexedThumb: Identifiable {
    let thumb: EhThumbnail
    var id: Int { Int(thumb.page) - 1 }
}

struct EhPagesView: View {
    let route: EhPagesRoute
    @Environment(\.galleryNamespace) private var namespace
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
        .navigationBarTitleDisplayMode(.inline)
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
                .readerThumbSource(gallery: route.gid, page: Int(thumb.page) - 1, namespace)
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
/// An E-Hentai search (a tag, an uploader): the same results page as
/// nhentai's tag searches, on E-Hentai first.
struct EhListView: View {
    let route: EhSearchRoute
    @State private var site: SiteKind = .ehentai

    var body: some View {
        SearchResults(query: route.query, site: $site)
            .navigationTitle(route.title)
            .navigationBarTitleDisplayMode(.inline)
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
            ForEach(comments.filter {
                !settings.blockedCommenterSet.contains($0.author) && !Filters.shared.hidesComment(author: $0.author, text: $0.text)
            }, id: \.id) { comment in
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

/// Archive download: the original or resampled zip (costs GP), shared or saved to Files when done.
struct EhArchiveView: View {
    let detail: EhGalleryDetail
    @State private var info: EhArchiveInfo?
    @State private var error: String?
    @State private var confirming: EhArchiveOption?
    @State private var downloading = false
    @State private var file: URL?

    var body: some View {
        List {
            if let error {
                ContentUnavailableView("Couldn't Load", systemImage: "wifi.exclamationmark", description: Text(error))
            } else if let info {
                Section {
                    ForEach(info.options, id: \.type) { option in
                        Button {
                            confirming = option
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(option.label).foregroundStyle(.primary)
                                Text([option.cost, option.size].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .disabled(downloading)
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("The archive is a zip of the whole gallery from E-Hentai's servers. Its cost is taken from your account's GP.")
                        if let funds = info.funds { Text("Funds: \(funds)") }
                    }
                }
                if downloading {
                    Section { HStack { ProgressView(); Text("Downloading…").foregroundStyle(.secondary) } }
                }
                if let file {
                    Section {
                        Button("Share or Save to Files", systemImage: "square.and.arrow.up") { ShareSheet.present([file]) }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Download Archive")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { info = try await EhService.shared.archiveInfo(detail) } catch { self.error = error.localizedDescription }
        }
        .confirmationDialog("Download Archive?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible) {
            Button("Download") {
                guard let option = confirming else { return }
                Task { await download(option) }
            }
        } message: {
            Text([confirming?.cost ?? "", confirming?.size ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
        }
    }

    private func download(_ option: EhArchiveOption) async {
        downloading = true
        defer { downloading = false }
        do {
            let link = try await EhService.shared.requestArchive(detail, option: option)
            guard let url = URL(string: link) else { throw URLError(.badURL) }
            let (temp, response) = try await URLSession.shared.download(from: url)
            guard (response as? HTTPURLResponse)?.statusCode ?? 200 < 400 else { throw URLError(.badServerResponse) }
            let name = String(detail.title.map { "/\\:*?\"<>|".contains($0) ? "_" : $0 }.prefix(120))
            let target = FileManager.default.temporaryDirectory.appendingPathComponent((name.isEmpty ? "\(detail.gid)" : name) + ".zip")
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temp, to: target)
            file = target
            ShareSheet.present([target])
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Votes on a gallery's tags, and adds tags (E-Hentai tag voting).
struct EhTagVoteView: View {
    let detail: EhGalleryDetail
    @State private var adding = ""
    @State private var voted: [String: Bool] = [:]
    @State private var error: String?
    @Environment(TagStore.self) private var tagStore

    var body: some View {
        List {
            Section {
                TextField("female:glasses, artist:name", text: $adding)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Add") {
                    let tags = adding.trimmingCharacters(in: .whitespaces)
                    adding = ""
                    Task { await vote(tags, up: true, mark: nil) }
                }
                .disabled(adding.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: {
                Text("Add Tags")
            } footer: {
                Text("Separate tags with commas, with a namespace: female:glasses, artist:name. Adding votes for them.")
            }
            ForEach(Array(detail.tags.keys).sorted(), id: \.self) { namespace in
                Section(namespace) {
                    ForEach(detail.tags[namespace] ?? [], id: \.name) { tag in
                        let full = "\(namespace):\(tag.name)"
                        HStack {
                            VStack(alignment: .leading) {
                                Text(tagStore.displayName(tag.name, type: namespace))
                                if tagStore.displayName(tag.name, type: namespace) != tag.name {
                                    Text(tag.name).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button { Task { await vote(full, up: true, mark: full) } } label: {
                                Image(systemName: voted[full] == true ? "hand.thumbsup.fill" : "hand.thumbsup")
                            }
                            .buttonStyle(.borderless)
                            .tint(voted[full] == true ? .green : .secondary)
                            Button { Task { await vote(full, up: false, mark: full) } } label: {
                                Image(systemName: voted[full] == false ? "hand.thumbsdown.fill" : "hand.thumbsdown")
                            }
                            .buttonStyle(.borderless)
                            .tint(voted[full] == false ? .red : .secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Vote on Tags")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Couldn't Vote", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    private func vote(_ tags: String, up: Bool, mark: String?) async {
        do {
            if let message = try await EhService.shared.voteTags(detail, tags: tags, up: up) {
                error = message
            } else if let mark {
                voted[mark] = up
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
