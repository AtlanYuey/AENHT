import SwiftUI
import UIKit

/// Retains the navigation zoom while keeping its interior gestures away
/// from the scroll views that pan and resize the reader's artwork.
struct ReaderBackGestures: UIViewControllerRepresentable {
    let allowsEdge: Bool
    let prepare: () -> Void
    let back: () -> Void

    func makeUIViewController(context: Context) -> Controller { Controller() }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.prepare = prepare
        controller.back = back
        controller.allowsEdge = allowsEdge
    }

    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) {
        controller.restore()
    }

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        var allowsEdge = true {
            didSet { if oldValue != allowsEdge { updateEdges() } }
        }
        var prepare: (() -> Void)?
        var back: (() -> Void)?
        private weak var navigation: UINavigationController?
        private weak var readerHost: UIViewController?
        private var nativeEdge: UIGestureRecognizer?
        private weak var nativeDelegate: UIGestureRecognizerDelegate?
        private var nativeEdgeEnabled = false
        private var nativeMaximumTouches: Int?
        private var fallbackEdge: UIScreenEdgePanGestureRecognizer?
        private var paused: [ObjectIdentifier: UIGestureRecognizer] = [:]

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard let navigation = navigationController else { return }
            if self.navigation !== navigation {
                restore()
                self.navigation = navigation
                readerHost = navigation.topViewController
                nativeEdge = navigation.interactivePopGestureRecognizer
                nativeDelegate = nativeEdge?.delegate
                nativeEdgeEnabled = nativeEdge?.isEnabled ?? false
                if let pan = nativeEdge as? UIPanGestureRecognizer {
                    nativeMaximumTouches = pan.maximumNumberOfTouches
                    pan.maximumNumberOfTouches = 1
                }

                let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(handleEdge(_:)))
                edge.edges = .left
                edge.maximumNumberOfTouches = 1
                edge.delegate = self
                if let nativeEdge { edge.require(toFail: nativeEdge) }
                navigation.view.addGestureRecognizer(edge)
                fallbackEdge = edge
            }
            updateGestures()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            updateGestures()
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            restore()
        }

        private func updateEdges() {
            nativeEdge?.delegate = self
            let nativeAllowed = nativeEdgeEnabled && allowsEdge
            if nativeEdge?.isEnabled != nativeAllowed { nativeEdge?.isEnabled = nativeAllowed }
            if fallbackEdge?.isEnabled != allowsEdge { fallbackEdge?.isEnabled = allowsEdge }
        }

        private func updateGestures() {
            guard let navigation, navigation.topViewController === readerHost else { return }
            if #available(iOS 26.0, *), let contentPop = navigation.interactiveContentPopGestureRecognizer,
               contentPop !== nativeEdge {
                pause(contentPop)
            }
            // Zoom attaches its dismissal recognizers to the navigation host.
            // The image pinch and pan live on child scroll views.
            var view = self.view.superview
            while let ancestor = view, ancestor === navigation.view || ancestor.isDescendant(of: navigation.view) {
                pauseNavigationGestures(on: ancestor)
                if ancestor === navigation.view { break }
                view = ancestor.superview
            }
            if let host = navigation.topViewController?.viewIfLoaded {
                pauseNavigationGestures(on: host)
            }
            pauseNavigationGestures(on: navigation.view)
            updateEdges()
        }

        private func pauseNavigationGestures(on view: UIView) {
            guard !(view is UIScrollView) else { return }
            for gesture in view.gestureRecognizers ?? [] where gesture !== nativeEdge && gesture !== fallbackEdge {
                if gesture is UIPanGestureRecognizer || gesture is UIPinchGestureRecognizer || gesture is UISwipeGestureRecognizer {
                    pause(gesture)
                }
            }
        }

        private func pause(_ gesture: UIGestureRecognizer) {
            guard gesture.isEnabled else { return }
            paused[ObjectIdentifier(gesture)] = gesture
            gesture.isEnabled = false
        }

        func restore() {
            for gesture in paused.values { gesture.isEnabled = true }
            paused.removeAll()
            if let nativeEdge {
                nativeEdge.delegate = nativeDelegate
                nativeEdge.isEnabled = nativeEdgeEnabled
                if let pan = nativeEdge as? UIPanGestureRecognizer, let nativeMaximumTouches {
                    pan.maximumNumberOfTouches = nativeMaximumTouches
                }
            }
            if let fallbackEdge { fallbackEdge.view?.removeGestureRecognizer(fallbackEdge) }
            fallbackEdge = nil
            nativeEdge = nil
            nativeDelegate = nil
            nativeMaximumTouches = nil
            navigation = nil
            readerHost = nil
        }

        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard allowsEdge, let navigation, navigation.topViewController === readerHost, navigation.viewControllers.count > 1,
                  navigation.transitionCoordinator == nil, let pan = gesture as? UIPanGestureRecognizer
            else { return false }
            let translation = pan.translation(in: navigation.view)
            let motion = translation == .zero ? pan.velocity(in: navigation.view) : translation
            let start = pan.location(in: navigation.view).x - translation.x
            guard (gesture is UIScreenEdgePanGestureRecognizer || start <= 24),
                  motion.x > 0, abs(motion.x) > abs(motion.y)
            else { return false }
            prepare?()
            navigation.viewControllers.dropLast().last?.viewIfLoaded?.layoutIfNeeded()
            return true
        }

        func gestureRecognizer(_ gesture: UIGestureRecognizer,
                               shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            allowsEdge && other is UIPanGestureRecognizer && other !== nativeEdge && other !== fallbackEdge
        }

        @objc private func handleEdge(_ pan: UIScreenEdgePanGestureRecognizer) {
            guard pan.state == .ended, allowsEdge, let navigation else { return }
            let distance = pan.translation(in: navigation.view).x
            let velocity = pan.velocity(in: navigation.view).x
            if distance >= 44 || (distance >= 12 && velocity > 550) { back?() }
        }
    }
}
