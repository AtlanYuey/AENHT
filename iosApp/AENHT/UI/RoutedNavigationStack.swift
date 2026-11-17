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
            // The pages keep the accent; the bar (its back button) is in the label color.
            content().tint(AppSettings.shared.accent.color)
        }
        .tint(.primary)
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
/// touches while that animation runs: on iOS 18 a touch during the zoom
/// back to the card catches the animation and turns it around, and the same
/// touch then landed on the page (opening the page of the gallery that was
/// closing). The app takes no touches until the animation has ended (a
/// moment); a back swipe, which the finger drives itself, isn't touched.
struct LeavingPageIgnoresTouches: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Watcher { Watcher() }
    func updateUIViewController(_ controller: Watcher, context: Context) {}

    final class Watcher: UIViewController {
        override func viewDidLoad() {
            super.viewDidLoad()
            view.isUserInteractionEnabled = false
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            guard animated, let window = view.window else { return }
            let coordinator = transitionCoordinator
            guard coordinator?.isInteractive != true else { return }
            window.isUserInteractionEnabled = false
            coordinator?.animate(alongsideTransition: nil) { _ in window.isUserInteractionEnabled = true }
            // Whatever happens to the animation, never longer than this.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { window.isUserInteractionEnabled = true }
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            view.window?.isUserInteractionEnabled = true
        }
    }
}

extension View {
    /// A page pushed on a stack: its controls (switches, buttons, links) in the
    /// accent. Pushed pages take the stack's environment, whose tint is the
    /// label color for the back button.
    func pageTint() -> some View {
        tint(AppSettings.shared.accent.color)
    }

    /// The bar's buttons in the label color (white in Dark Mode, black in
    /// Light), not the accent: the page under the bar keeps the accent.
    func barToolbar<Content: ToolbarContent>(@ToolbarContentBuilder _ content: () -> Content) -> some View {
        self.tint(AppSettings.shared.accent.color)
            .toolbar(content: content)
            .tint(.primary)
    }
}
