import Shared
import SwiftUI

struct CommentsView: View {
    let galleryID: Int
    @State private var comments: [GalleryComment]
    @State private var draft = ""
    @State private var isPosting = false
    @State private var error: String?
    /// The last page read and how many the site has.
    @State private var page = 1
    @State private var pageCount = 1
    @State private var loadingMore = false
    /// Counts the reloads: a later page read for an earlier list isn't added to this one.
    @State private var generation = 0
    /// Why the comments couldn't be read (shown instead of "No Comments" when there are none to show).
    @State private var loadError: String?
    @FocusState private var composing: Bool
    @Environment(UserSession.self) private var session

    init(galleryID: Int, initial: [GalleryComment]) {
        self.galleryID = galleryID
        _comments = State(initialValue: initial)
    }

    var body: some View {
        List {
            ForEach(comments.filter { !Filters.shared.hidesComment(author: $0.poster?.username ?? "", text: $0.body) }) { comment in
                CommentBubble(comment: comment)
                    .padding(.vertical, 4)
            }
            // More pages: the next one loads when the end shows.
            if !comments.isEmpty, page < pageCount {
                HStack {
                    Spacer()
                    if loadError != nil, !loadingMore {
                        Button("Retry") { Task { await loadMore() } }.buttonStyle(.bordered)
                    } else {
                        ProgressView().task(id: page) { await loadMore() }
                    }
                    Spacer()
                }
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .overlay {
            if comments.isEmpty, let loadError {
                ContentUnavailableView {
                    Label("Couldn't Load", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(verbatim: loadError)
                } actions: {
                    Button("Try Again") { Task { await load() } }.buttonStyle(.borderedProminent)
                }
            } else if comments.isEmpty {
                ContentUnavailableView("No Comments", systemImage: "text.bubble")
            }
        }
        .navigationTitle("Comments")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .safeAreaInset(edge: .bottom) {
            if session.isLoggedIn { composer }
        }
        .alert("Couldn't Post", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
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

    private func load() async {
        generation += 1
        let mine = generation
        loadingMore = false
        do {
            let first = try await NHClient.shared.commentsPage(galleryID, page: 1)
            guard mine == generation else { return }
            comments = first.result
            page = 1
            pageCount = first.numPages
            loadError = nil
        } catch is CancellationError {
        } catch {
            if mine == generation { loadError = ErrorText.of(error) }
        }
    }

    private func loadMore() async {
        guard !loadingMore, page < pageCount else { return }
        // A page read for the list before a refresh isn't added to the new one.
        let mine = generation
        let wanted = page + 1
        loadingMore = true
        defer { if mine == generation { loadingMore = false } }
        do {
            let more = try await NHClient.shared.commentsPage(galleryID, page: wanted)
            guard mine == generation else { return }
            let known = Set(comments.map(\.id))
            comments += more.result.filter { !known.contains($0.id) }
            pageCount = more.numPages
            page = wanted
            loadError = nil
        } catch is CancellationError {
        } catch {
            if mine == generation { loadError = ErrorText.of(error) }
        }
    }

    private func post() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isPosting = true
        defer { isPosting = false }
        do {
            let comment = try await NHClient.shared.postComment(galleryID, body: text)
            comments.insert(comment, at: 0)
            draft = ""
            composing = false
        } catch {
            self.error = ErrorText.of(error)
        }
    }
}

/// Glass on iOS 26, the bar material before.
struct BarBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
        } else {
            content.background(.bar)
        }
    }
}

struct CommentBubble: View {
    let comment: GalleryComment
    var lineLimit: Int? = nil

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RemoteImage(url: comment.poster?.avatar, targetWidth: 80)
                .frame(width: 34, height: 34)
                .clipShape(Circle())
                .overlay {
                    if comment.poster?.avatar == nil {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 34))
                            .foregroundStyle(.secondary)
                    }
                }
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(comment.poster?.username ?? String(localized: "Anonymous"))
                        .font(.subheadline.weight(.semibold))
                    Spacer(minLength: 8)
                    Text(comment.date, format: .relative(presentation: .named))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(comment.body).privacyText()
                    .font(.subheadline)
                    .lineLimit(lineLimit)
                    .textSelection(.enabled)
                if lineLimit == nil {
                    TranslateLine(text: comment.body, kind: .comment)
                }
            }
        }
    }
}
