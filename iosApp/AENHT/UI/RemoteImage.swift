import Nuke
import NukeUI
import SwiftUI

enum RemoteImageLoadState: Equatable {
    case loading, ready(CGSize), failed
}

/// An image from the network, cached on disk (Nuke), with a neutral
/// placeholder while loading.
struct RemoteImage: View {
    let url: URL?
    var contentMode: ContentMode = .fill
    /// Decode at this width (points) to save memory; nil keeps full size.
    var targetWidth: CGFloat? = 400
    var blur: Bool = false
    /// Where to load the image from when [url] fails (asked only then).
    var fallback: (() -> URL?)? = nil
    /// Reader pages need an explicit recovery action without leaving their scroll position.
    var retryOnFailure = false
    /// Allows continuous reading to share its display request with image preparation.
    var makeRequest: ((URL) -> ImageRequest)? = nil
    var enhancement: ReaderImageEnhancement? = nil
    var enhanceNow = false
    /// Shown while it loads (a reader page's thumbnail, already in memory), instead of an empty box.
    var placeholder: UIImage? = nil
    var onLoadState: ((RemoteImageLoadState) -> Void)? = nil
    @State private var attempt = 0
    /// The picture couldn't be loaded: it is loaded again when the network comes back.
    @State private var failed = false
    @Environment(\.privacyMode) private var privacy

    var body: some View {
        LazyImage(request: request) { state in
            if let image = state.image {
                Group {
                    if let enhancement, let original = state.imageContainer?.image {
                        EnhancedRemoteImage(original: original, enhancement: enhancement, active: enhanceNow,
                                            contentMode: contentMode)
                    } else {
                        image.resizable().aspectRatio(contentMode: contentMode)
                    }
                }
                .blur(radius: blur ? 18 : privacy == .blurImages || privacy == .blurContent ? 28 : 0, opaque: true)
                .transition(.opacity)
                .onAppear { onLoadState?(.ready(state.imageContainer?.image.size ?? .zero)) }
            } else if state.error != nil, let other = fallback?(), other != url {
                RemoteImage(url: other, contentMode: contentMode, targetWidth: targetWidth, blur: blur,
                            retryOnFailure: retryOnFailure, makeRequest: makeRequest,
                            enhancement: enhancement, enhanceNow: enhanceNow, onLoadState: onLoadState)
            } else if state.error != nil {
                Rectangle()
                    .fill(.quaternary)
                    .onAppear { failed = true; onLoadState?(.failed) }
                    .overlay {
                        VStack(spacing: 12) {
                            Image(systemName: "photo")
                                .font(.title3)
                                .foregroundStyle(.tertiary)
                            if retryOnFailure {
                                Button("Retry") {
                                    failed = false
                                    onLoadState?(.loading)
                                    attempt += 1
                                }
                                    .buttonStyle(.bordered)
                                    .frame(minHeight: 44)
                            }
                        }
                    }
            } else if let placeholder {
                Image(uiImage: placeholder).resizable().aspectRatio(contentMode: contentMode)
            } else {
                Rectangle().fill(.quaternary.opacity(0.6))
            }
        }
        .onStart { _ in onLoadState?(.loading) }
        .onCompletion { result in
            if case .success(let response) = result { onLoadState?(.ready(response.image.size)) }
        }
        .id(attempt)
        .onChange(of: Connectivity.shared.restored) {
            guard failed else { return }
            failed = false
            attempt += 1
        }
        .animation(.easeOut(duration: 0.2), value: url)
    }

    private var request: ImageRequest? {
        guard let url else { return nil }
        if let makeRequest { return makeRequest(url) }
        var processors: [any ImageProcessing] = []
        if let targetWidth {
            processors.append(.resize(width: targetWidth))
        }
        return ImageRequest(url: url, processors: processors)
    }
}

private struct EnhancedRemoteImage: View {
    let original: UIImage
    let enhancement: ReaderImageEnhancement
    let active: Bool
    let contentMode: ContentMode
    @State private var result: (ReaderImageEnhancement, UIImage)?

    var body: some View {
        let prepared = (result?.0 == enhancement ? result?.1 : nil) ?? enhancement.cached(original)
        Image(uiImage: prepared ?? original)
            .resizable()
            .aspectRatio(contentMode: contentMode)
            .task(id: active ? enhancement : nil) {
                guard active, prepared == nil,
                      let image = await enhancement.enhance(original), !Task.isCancelled else { return }
                result = (enhancement, image)
            }
    }
}

enum ImageSetup {
    /// Disk-cached pipeline for covers and pages.
    static func configure() {
        var configuration = ImagePipeline.Configuration.withDataCache(
            name: "com.atlanyuey.aenht.images",
            sizeLimit: 600 * 1024 * 1024
        )
        // E-Hentai reader pages (ehpage://) are looked up before loading.
        configuration.dataLoader = EhPageDataLoader()
        ImagePipeline.shared = ImagePipeline(configuration: configuration)
    }

    static func clearCache() {
        ImagePipeline.shared.cache.removeAll()
        URLCache.shared.removeAllCachedResponses()
    }

    static var diskUsage: Int {
        (ImagePipeline.shared.configuration.dataCache as? DataCache)?.totalSize ?? 0
    }
}
