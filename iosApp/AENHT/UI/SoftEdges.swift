import SwiftUI

/// A row of covers cut off at [edges] (where it meets the other half of a
/// wide page): the covers end in round corners like their own, as if cut by
/// a card. The titles under them ([imageHeight] on) are cut straight.
private struct CutCards: ViewModifier {
    let edges: Edge.Set
    let imageHeight: CGFloat
    var radius: CGFloat = 12

    func body(content: Content) -> some View {
        let leading = edges.contains(.leading)
        let trailing = edges.contains(.trailing)
        // Always masked (rounding 0 when nothing is cut), so turning the device keeps the row's place.
        content.mask {
            VStack(spacing: 0) {
                UnevenRoundedRectangle(topLeadingRadius: leading ? radius : 0, bottomLeadingRadius: leading ? radius : 0,
                                       bottomTrailingRadius: trailing ? radius : 0, topTrailingRadius: trailing ? radius : 0,
                                       style: .continuous)
                    .frame(height: imageHeight)
                Color.black
            }
        }
    }
}

extension View {
    /// Cut off at [edges]: covers ([imageHeight] tall) with round corners.
    func cutCards(_ edges: Edge.Set, imageHeight: CGFloat) -> some View {
        modifier(CutCards(edges: edges, imageHeight: imageHeight))
    }
}

/// The top and bottom of a scrolling pane under the bars: the system's own
/// soft scroll edge (iOS 26), like every other list in the app, for this
/// pane too (it scrolls on its own beside another one).
private struct PaneScrollEdges: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .scrollEdgeEffectStyle(.soft, for: .all)
                .scrollEdgeEffectHidden(false, for: .all)
        } else {
            content
        }
    }
}

extension View {
    /// The top and bottom of a scrolling pane blur and fade out under the bars.
    func paneScrollEdges() -> some View { modifier(PaneScrollEdges()) }
}
