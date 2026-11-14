import UIKit

/// The system share sheet over the screen in front (for things made on
/// demand: a page image, a CBZ file).
enum ShareSheet {
    @MainActor
    static func present(_ items: [Any]) {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        controller.popoverPresentationController?.sourceView = top?.view
        controller.popoverPresentationController?.sourceRect = CGRect(x: top?.view.bounds.midX ?? 0, y: 60, width: 1, height: 1)
        top?.present(controller, animated: true)
    }
}
