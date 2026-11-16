import SwiftUI

/// Scrolling a list down hides the tab bar (when Appearance › Hide Tab Bar
/// When Scrolling is on); scrolling up, or reaching the top, shows it
/// again. The tab bar only slides away: the space it takes stays, and the
/// navigation bar is never touched, so the list never moves.
struct HidesBarsOnScroll: ViewModifier {
    @Environment(AppSettings.self) private var settings
    /// Scroll bookkeeping. A reference, not state: it changes on every
    /// frame and must not redraw anything.
    @State private var hiding = ScrollBarsHiding()

    func body(content: Content) -> some View {
        content
            .onScrollGeometryChange(for: ScrollSample.self) { geometry in
                ScrollSample(
                    offset: geometry.contentOffset.y + geometry.contentInsets.top,
                    topInset: geometry.contentInsets.top,
                    maxOffset: geometry.contentSize.height + geometry.contentInsets.top
                        + geometry.contentInsets.bottom - geometry.containerSize.height
                )
            } action: { _, new in
                hiding.update(offset: new.offset, topInset: new.topInset, maxOffset: new.maxOffset)
            }
            .onChange(of: settings.hideBarsOnScroll) { _, on in
                if !on { hiding.reset() }
            }
            .onDisappear { hiding.reset() }
    }
}

/// The tab bar's hiding as a list scrolls (SwiftUI lists through
/// `hidesBarsOnScroll`, the gallery lists from their collection view).
@MainActor
final class ScrollBarsHiding {
    /// Distance scrolled in the current direction.
    private var travel: CGFloat = 0
    private var last: (offset: CGFloat, topInset: CGFloat, maxOffset: CGFloat)?

    /// [offset] from the top of the list (0 there), [maxOffset] the furthest it goes.
    func update(offset: CGFloat, topInset: CGFloat, maxOffset: CGFloat) {
        let old = last
        last = (offset, topInset, maxOffset)
        guard AppSettings.shared.hideBarsOnScroll, let old else { return }
        // Inset changes (the large title collapsing) are not the user scrolling.
        if topInset != old.topInset {
            travel = 0
            return
        }
        if offset < 60 {
            travel = 0
            setHidden(false)
            return
        }
        // The bounce at the end of the list is not a scroll up.
        guard offset <= maxOffset, old.offset <= old.maxOffset else { return }
        let delta = offset - old.offset
        guard delta != 0 else { return }
        if (delta > 0) != (travel > 0) { travel = 0 }
        travel += delta
        if travel > 24 {
            setHidden(true)
        } else if travel < -24 {
            setHidden(false)
        }
    }

    /// The bars show again (the list went away, or the setting was turned off).
    func reset() {
        last = nil
        setHidden(false)
    }

    private func setHidden(_ hidden: Bool) {
        let chrome = AppChrome.shared
        guard chrome.barsHidden != hidden else { return }
        travel = 0
        withAnimation(.smooth(duration: 0.28)) { chrome.barsHidden = hidden }
    }
}

private struct ScrollSample: Equatable {
    let offset: CGFloat
    let topInset: CGFloat
    let maxOffset: CGFloat
}

extension View {
    func hidesBarsOnScroll() -> some View {
        modifier(HidesBarsOnScroll())
    }
}

/// The navigation bar's subtitle under the title (iOS 26; none before).
struct NavigationSubtitle: ViewModifier {
    let text: String

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.navigationSubtitle(text)
        } else {
            content
        }
    }
}
