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
        let tab: AppTab

        init(tab: AppTab) {
            self.tab = tab
            super.init(nibName: nil, bundle: nil)
            view.isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            if let navigation = navigationController { TabNavigators.shared.set(navigation, for: tab) }
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            if let navigation = navigationController { TabNavigators.shared.set(navigation, for: tab) }
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
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top
            } action: { _, new in
                offset = new
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
