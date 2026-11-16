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

/// Soft edges at the top and the bottom of a scrolling pane, under the bars,
/// like iOS 26's scroll edge effect: the content blurs and fades out there
/// instead of ending on a line.
private struct PaneScrollEdges: ViewModifier {
    func body(content: Content) -> some View {
        content
            .modifier(NoSystemEdgeEffect())
            .overlay {
                GeometryReader { proxy in
                    VStack(spacing: 0) {
                        edge(top: true).frame(height: proxy.safeAreaInsets.top + 14)
                        Spacer(minLength: 0)
                        edge(top: false).frame(height: proxy.safeAreaInsets.bottom + 14)
                    }
                    .ignoresSafeArea()
                }
                .allowsHitTesting(false)
            }
    }

    /// Blurred, then fading into the page's background (which follows Dark
    /// Mode), like the system's soft edge.
    private func edge(top: Bool) -> some View {
        let mask = LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.45), .init(color: .clear, location: 1)],
                                  startPoint: top ? .top : .bottom, endPoint: top ? .bottom : .top)
        return ZStack {
            Rectangle().fill(.ultraThinMaterial)
            Color(.systemBackground).opacity(0.85)
        }
        .mask(mask)
    }
}

/// The system's own edge effect is left out where the pane draws its own (not both).
private struct NoSystemEdgeEffect: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.scrollEdgeEffectHidden(true, for: .vertical)
        } else {
            content
        }
    }
}

extension View {
    /// The top and bottom of a scrolling pane blur and fade out under the bars.
    func paneScrollEdges() -> some View { modifier(PaneScrollEdges()) }
}
