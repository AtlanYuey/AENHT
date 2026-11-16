import SwiftUI

/// Fades content out at its edges: what a pane cuts off ends in a soft edge,
/// not a straight line.
private struct FadedEdges: ViewModifier {
    let edges: Edge.Set
    let length: CGFloat

    func body(content: Content) -> some View {
        content
            .mask {
                HStack(spacing: 0) {
                    LinearGradient(colors: [.clear, .black], startPoint: .leading, endPoint: .trailing)
                        .frame(width: edges.contains(.leading) ? length : 0)
                    Color.black
                    LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: edges.contains(.trailing) ? length : 0)
                }
                // Over the bars too (the content scrolls under them).
                .ignoresSafeArea()
            }
            .mask {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                        .frame(height: edges.contains(.top) ? length : 0)
                    Color.black
                    LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: edges.contains(.bottom) ? length : 0)
                }
                .ignoresSafeArea(edges: edges.contains(.top) || edges.contains(.bottom) ? [] : .all)
            }
    }
}

extension View {
    /// Fades out over [length] at [edges] (0: nothing fades, without rebuilding the content).
    func fadedEdges(_ edges: Edge.Set, length: CGFloat = 28) -> some View {
        modifier(FadedEdges(edges: edges, length: length))
    }
}

/// A row of covers cut off at [edges] (where it meets the other half of a
/// wide page): the covers end in round corners like their own, as if cut by
/// a card, and the titles under them ([imageHeight] on) fade out.
private struct CutCards: ViewModifier {
    let edges: Edge.Set
    let imageHeight: CGFloat
    var radius: CGFloat = 12
    var fade: CGFloat = 36

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
                HStack(spacing: 0) {
                    LinearGradient(colors: [.clear, .black], startPoint: .leading, endPoint: .trailing)
                        .frame(width: leading ? fade : 0)
                    Color.black
                    LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: trailing ? fade : 0)
                }
            }
        }
    }
}

extension View {
    /// Cut off at [edges]: covers ([imageHeight] tall) with round corners, titles fading out.
    func cutCards(_ edges: Edge.Set, imageHeight: CGFloat) -> some View {
        modifier(CutCards(edges: edges, imageHeight: imageHeight))
    }
}

/// Soft edges at the top and the bottom of a scrolling pane, under the bars,
/// like iOS 26's scroll edge effect: the content blurs and fades out there
/// instead of ending on a line.
private struct SoftScrollEdges: ViewModifier {
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

    private func edge(top: Bool) -> some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.45), .init(color: .clear, location: 1)],
                                 startPoint: top ? .top : .bottom, endPoint: top ? .bottom : .top))
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
    func softScrollEdges() -> some View { modifier(SoftScrollEdges()) }
}
