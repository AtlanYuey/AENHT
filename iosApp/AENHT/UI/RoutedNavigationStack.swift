import SwiftUI
import UIKit

extension EnvironmentValues {
    /// Opens a gallery page in the navigation stack this view is in, as a
    /// link to it would (the gallery lists' cards are UIKit cells, out of the
    /// links' reach; nhentai's random gallery).
    @Entry var openRoute: ((OpenedGallery) -> Void)? = nil
}

/// A navigation stack with its path, so a gallery can be opened in it from
/// code (`openRoute`) the same way its links open pages: one path, one kind
/// of destination, so the pages opened from the gallery (its pages, the
/// reader) stack on it in order.
struct RoutedNavigationStack<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @State private var path = NavigationPath()
    /// The last gallery opened from code: a second tap while it opens doesn't open another.
    @State private var lastOpened = Date.distantPast

    var body: some View {
        NavigationStack(path: $path) {
            content()
        }
        .environment(\.openRoute) { route in
            guard Date().timeIntervalSince(lastOpened) > 0.8 else { return }
            lastOpened = Date()
            switch route {
            case let .nh(gallery): path.append(gallery)
            case let .eh(gallery): path.append(gallery)
            }
        }
    }
}


/// A page that is being closed (or covered by the next one) doesn't take
/// touches: a tap while its closing animation still runs goes to the page
/// under it, the one it is going back to, instead of opening something on
/// the page that is going away.
struct LeavingPageIgnoresTouches: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Watcher { Watcher() }
    func updateUIViewController(_ controller: Watcher, context: Context) {}

    final class Watcher: UIViewController {
        /// The page: the view of the controller the navigation controller shows.
        private var page: UIView? {
            var controller: UIViewController? = parent
            while let current = controller, !(current.parent is UINavigationController) { controller = current.parent }
            return controller?.view
        }

        override func viewDidLoad() {
            super.viewDidLoad()
            view.isUserInteractionEnabled = false
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            page?.isUserInteractionEnabled = true
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            page?.isUserInteractionEnabled = false
        }
    }
}
