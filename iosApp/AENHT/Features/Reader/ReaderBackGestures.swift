import SwiftUI
import UIKit

/// Gives the reader a release-to-return edge gesture and a noninteractive
/// UIKit zoom to the reading page's thumbnail.
struct ReaderBackGestures: UIViewControllerRepresentable {
    let allowsEdge: Bool
    let openingThumbnail: ReaderThumbnailID
    let returnTarget: ReaderThumbnailID?
    let back: () -> Void
    let dismiss: () -> Void

    func makeUIViewController(context: Context) -> Controller { Controller() }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.back = back
        controller.dismiss = dismiss
        controller.openingThumbnail = openingThumbnail
        controller.allowsEdge = allowsEdge
        if let returnTarget { controller.startReturn(to: returnTarget) }
    }

    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) {
        controller.restore()
    }

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        var allowsEdge = true {
            didSet { if oldValue != allowsEdge { edge?.isEnabled = allowsEdge } }
        }
        var openingThumbnail: ReaderThumbnailID?
        var back: (() -> Void)?
        var dismiss: (() -> Void)?
        private weak var navigation: UINavigationController?
        private weak var readerHost: UIViewController?
        private var edge: UIScreenEdgePanGestureRecognizer?
        private var paused: [ObjectIdentifier: UIGestureRecognizer] = [:]
        private var originalTransition: UIViewController.Transition?
        private var transition: UIViewController.Transition?
        private var target: ReaderThumbnailID?
        private var pendingReturn: Task<Void, Never>?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let navigation = navigationController, let host = navigation.topViewController else { return }
            if readerHost !== host {
                restore()
                self.navigation = navigation
                readerHost = host
                originalTransition = host.preferredTransition
                let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(handleEdge(_:)))
                edge.edges = .left
                edge.maximumNumberOfTouches = 1
                edge.delegate = self
                edge.isEnabled = allowsEdge
                navigation.view.addGestureRecognizer(edge)
                self.edge = edge

                let options = UIViewController.Transition.ZoomOptions()
                options.interactiveDismissShouldBegin = { _ in false }
                transition = .zoom(options: options) { [weak self] context in
                    guard let self, let id = self.target ?? self.openingThumbnail else { return nil }
                    let source = context.sourceViewController.view
                    source?.layoutIfNeeded()
                    let thumbnail = source?.readerThumbnail(id)
                    #if DEBUG
                    if ReaderGestureTestSupport.enabled, self.target != nil, thumbnail != nil {
                        ReaderGestureTestSupport.shared.returnedPage = id.page
                    }
                    #endif
                    return thumbnail
                }
            }
            updateNavigation()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            updateNavigation()
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            restore()
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            #if DEBUG
            if ReaderGestureTestSupport.enabled {
                ReaderGestureTestSupport.shared.edgeEvents.append("departure")
            }
            #endif
        }

        private func updateNavigation() {
            guard let navigation, let readerHost, navigation.topViewController === readerHost else { return }
            if readerHost.preferredTransition !== transition { readerHost.preferredTransition = transition }
            if let nativeEdge = navigation.interactivePopGestureRecognizer { pause(nativeEdge) }
            if #available(iOS 26.0, *), let contentPop = navigation.interactiveContentPopGestureRecognizer {
                pause(contentPop)
            }
            // The SwiftUI entrance can leave its own dismiss gestures on the
            // hosting view. The reader's scroll gestures live on child views.
            for view in [readerHost.viewIfLoaded, navigation.viewIfLoaded].compactMap({ $0 }) where !(view is UIScrollView) {
                for gesture in view.gestureRecognizers ?? [] where gesture !== edge {
                    if gesture is UIPanGestureRecognizer || gesture is UIPinchGestureRecognizer { pause(gesture) }
                }
            }
        }

        private func pause(_ gesture: UIGestureRecognizer) {
            guard gesture.isEnabled else { return }
            paused[ObjectIdentifier(gesture)] = gesture
            gesture.isEnabled = false
        }

        func startReturn(to id: ReaderThumbnailID) {
            guard target == nil, let navigation, navigation.topViewController === readerHost else { return }
            target = id
            pendingReturn = Task { [weak self, weak navigation] in
                guard let self, let navigation else { return }
                let previous = navigation.viewControllers.dropLast().last
                let source = previous?.viewIfLoaded
                let wasPositioned = source?.readerThumbnail(id) != nil
                if !wasPositioned {
                    source?.revealReaderThumbnail(id)
                    for _ in 0..<6 {
                        source?.setNeedsLayout()
                        source?.layoutIfNeeded()
                        if source?.readerThumbnail(id) != nil { break }
                        try? await Task.sleep(for: .milliseconds(16))
                        if Task.isCancelled { return }
                    }
                }
                guard !Task.isCancelled, navigation.topViewController === readerHost else { return }
                updateNavigation()
                dismiss?()
            }
        }

        func restore() {
            pendingReturn?.cancel()
            pendingReturn = nil
            for gesture in paused.values { gesture.isEnabled = true }
            paused.removeAll()
            if let readerHost, readerHost.preferredTransition === transition {
                readerHost.preferredTransition = originalTransition
            }
            if let edge { edge.view?.removeGestureRecognizer(edge) }
            edge = nil
            target = nil
            transition = nil
            originalTransition = nil
            readerHost = nil
            navigation = nil
        }

        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard allowsEdge, let navigation, navigation.topViewController === readerHost,
                  navigation.viewControllers.count > 1, navigation.transitionCoordinator == nil,
                  let edge = gesture as? UIScreenEdgePanGestureRecognizer
            else { return false }
            let motion = edge.translation(in: navigation.view)
            let velocity = edge.velocity(in: navigation.view)
            let direction = motion == .zero ? velocity : motion
            return direction.x > abs(direction.y)
        }

        func gestureRecognizer(_ gesture: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let view = gesture.view else { return false }
            // Limit priority to touches that actually began at the edge.
            return touch.location(in: view).x <= view.bounds.minX + 20
        }

        func gestureRecognizer(_ gesture: UIGestureRecognizer,
                               shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            allowsEdge && gesture === edge && other is UIPanGestureRecognizer
        }

        @objc private func handleEdge(_ pan: UIScreenEdgePanGestureRecognizer) {
            #if DEBUG
            if ReaderGestureTestSupport.enabled, pan.state == .began {
                ReaderGestureTestSupport.shared.edgeEvents.append("began")
            }
            #endif
            guard pan.state == .ended, allowsEdge, let navigation else { return }
            let delta = pan.translation(in: navigation.view)
            let minimum = max(80, min(136, navigation.view.bounds.width * 0.11))
            let completed = delta.x >= minimum && delta.x > abs(delta.y) * 1.5
            #if DEBUG
            if ReaderGestureTestSupport.enabled {
                ReaderGestureTestSupport.shared.edgeEvents.append(completed ? "released" : "short")
            }
            #endif
            if completed { back?() }
        }
    }
}
