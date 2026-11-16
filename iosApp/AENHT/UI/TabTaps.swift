import SwiftUI
import UIKit

/// A tab button tapped while its tab is showing.
struct TabTap: Equatable {
    var tab: AppTab
    var count: Int
}

/// The tabs' navigation controllers (behind SwiftUI's stacks), so a tab
/// button can go back in its tab.
@MainActor
final class TabNavigators {
    static let shared = TabNavigators()
    private var controllers: [AppTab: WeakNavigation] = [:]

    private final class WeakNavigation {
        weak var value: UINavigationController?
        init(_ value: UINavigationController?) { self.value = value }
    }

    func set(_ controller: UINavigationController?, for tab: AppTab) {
        controllers[tab] = WeakNavigation(controller)
        controller?.additionalSafeAreaInsets.bottom = effectiveInset
    }

    /// Room for the floating tab bar (iPad): every page of every tab, pushed
    /// ones included, keeps its content clear of it.
    var bottomInset: CGFloat = 0 { didSet { apply() } }
    /// The bar is hidden (the reader): no room kept, so the reader's own
    /// controls sit at the bottom.
    var barHidden = false { didSet { apply() } }

    private var effectiveInset: CGFloat { barHidden ? 0 : bottomInset }

    private func apply() {
        for entry in controllers.values where entry.value?.additionalSafeAreaInsets.bottom != effectiveInset {
            entry.value?.additionalSafeAreaInsets.bottom = effectiveInset
        }
    }

    func controller(for tab: AppTab) -> UINavigationController? { controllers[tab]?.value }
}

extension AppChrome {
    /// The tab button of the tab that shows was tapped. Me goes back one
    /// page; the others go back to their first page, and there their list
    /// goes to the top, back, or refreshes ([TabTapScrolling]).
    func tabReselected(_ tab: AppTab) {
        let tab = tab == .search ? .home : tab
        let navigation = TabNavigators.shared.controller(for: tab)
        if tab == .more {
            navigation?.popViewController(animated: true)
            return
        }
        if let navigation, navigation.viewControllers.count > 1 {
            navigation.popToRootViewController(animated: true)
            return
        }
        tabTap = TabTap(tab: tab, count: tabTap.count + 1)
    }
}

/// Finds the navigation controller of the tab it's in (put it in the
/// tab's first page).
struct TabNavigationFinder: UIViewControllerRepresentable {
    let tab: AppTab

    func makeUIViewController(context: Context) -> Finder {
        Finder(tab: tab)
    }

    func updateUIViewController(_ controller: Finder, context: Context) {}

    final class Finder: UIViewController {
        let appTab: AppTab

        init(tab: AppTab) {
            self.appTab = tab
            super.init(nibName: nil, bundle: nil)
            view.isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            if let navigation = navigationController { TabNavigators.shared.set(navigation, for: appTab) }
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            if let navigation = navigationController { TabNavigators.shared.set(navigation, for: appTab) }
        }
    }
}

/// A tab's list, when its tab button is tapped again on the first page:
/// away from the top, the list goes to the top; at the top, back to where
/// it was; tapped twice quickly, [refresh] runs.
struct TabTapScrolling: ViewModifier {
    let tab: AppTab
    var enabled = true
    let refresh: () async -> Void

    @State private var scroll = ScrollPosition(edge: .top)
    /// How far the list is scrolled (0 at the top).
    @State private var offset: CGFloat = 0
    @State private var place = KeptPlace()
    /// Where the list was when the tab button took it to the top.
    @State private var saved: CGFloat?
    /// A tap at the top, waiting to see if a second follows.
    @State private var pending: Task<Void, Never>?
    @State private var lastTopTap = Date.distantPast
    @State private var visible = false
    @State private var chrome = AppChrome.shared

    private static let doubleTap: TimeInterval = 0.3

    func body(content: Content) -> some View {
        content
            .scrollPosition($scroll)
            .onScrollGeometryChange(for: ScrollPlace.self) { geometry in
                ScrollPlace(geometry)
            } action: { old, new in
                offset = new.offset
                // Turned (or resized): the list stays where it was, not back at the top.
                place.changed(from: old, to: new) { scroll.scrollTo(y: $0) }
            }
            .background(TabNavigationFinder(tab: tab).frame(width: 0, height: 0))
            .onAppear { visible = true }
            .onDisappear { visible = false }
            .onChange(of: chrome.tabTap) { _, tap in
                if tap.tab == tab { tapped() }
            }
    }

    private func tapped() {
        guard visible, enabled else { return }
        if pending != nil || Date().timeIntervalSince(lastTopTap) < Self.doubleTap {
            pending?.cancel()
            pending = nil
            lastTopTap = .distantPast
            saved = nil
            withAnimation(.smooth) { scroll.scrollTo(edge: .top) }
            Task { await refresh() }
            return
        }
        if offset > 1 {
            saved = offset
            lastTopTap = Date()
            withAnimation(.smooth) { scroll.scrollTo(edge: .top) }
            return
        }
        pending = Task {
            try? await Task.sleep(for: .seconds(Self.doubleTap))
            guard !Task.isCancelled else { return }
            pending = nil
            guard let back = saved else { return }
            saved = nil
            withAnimation(.smooth) { scroll.scrollTo(y: back) }
        }
    }
}

extension View {
    func tabTapScrolling(_ tab: AppTab, enabled: Bool = true, refresh: @escaping () async -> Void) -> some View {
        modifier(TabTapScrolling(tab: tab, enabled: enabled, refresh: refresh))
    }
}

// MARK: - Keeping the place when the width changes

/// Where a list is: how far it is scrolled, how long it is and how wide.
struct ScrollPlace: Equatable {
    var offset: CGFloat
    var length: CGFloat
    var width: CGFloat

    init(_ geometry: ScrollGeometry) {
        offset = geometry.contentOffset.y + geometry.contentInsets.top
        length = geometry.contentSize.height
        width = geometry.containerSize.width
    }
}

/// Keeps a list where it was when its width changes (the device is turned,
/// Split View is resized): the cards are laid out again in another number
/// of columns, the list has another length, and without this it starts
/// again at the top. The place is kept as a share of the list's length.
@MainActor
final class KeptPlace {
    /// The place before the change, as a share of the list's length.
    private var share: CGFloat?
    private var length: CGFloat = 0
    private var task: Task<Void, Never>?

    func changed(from old: ScrollPlace, to new: ScrollPlace, scrollTo: @escaping (CGFloat) -> Void) {
        length = new.length
        guard old.width > 0, abs(new.width - old.width) > 1 else { return }
        // The first step of the change has the place as it was (later ones are already laid out again).
        if share == nil {
            guard old.offset > 1, old.length > 0 else { return }
            share = old.offset / old.length
        }
        // Once the new layout has settled.
        task?.cancel()
        task = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self, let share = self.share else { return }
            self.share = nil
            scrollTo(share * self.length)
        }
    }
}

/// For lists that aren't a tab's first page (those keep their place with `tabTapScrolling`).
private struct KeepsPlaceOnResize: ViewModifier {
    @State private var scroll = ScrollPosition(edge: .top)
    @State private var place = KeptPlace()

    func body(content: Content) -> some View {
        content
            .scrollPosition($scroll)
            .onScrollGeometryChange(for: ScrollPlace.self) { geometry in
                ScrollPlace(geometry)
            } action: { old, new in
                place.changed(from: old, to: new) { scroll.scrollTo(y: $0) }
            }
    }
}

extension View {
    /// Keeps this list where it was when the device is turned.
    func keepsPlaceOnResize() -> some View { modifier(KeepsPlaceOnResize()) }
}
