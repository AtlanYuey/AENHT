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

/// The right half of a wide page: a card with round corners next to the
/// details (not a straight cut), its content fading out at the top and the
/// bottom as it scrolls.
struct WideCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .fadedEdges(.vertical, length: 20)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .padding(.leading, 4)
            .padding([.trailing, .bottom], 12)
            .padding(.top, 8)
    }
}
