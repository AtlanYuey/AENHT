import SwiftUI

/// The background-privacy mode in effect now (`.off` while the app is in front).
private struct PrivacyModeKey: EnvironmentKey {
    static let defaultValue: BackgroundPrivacy = .off
}

extension EnvironmentValues {
    /// What to hide right now (In the Background setting, while the app isn't active).
    var privacyMode: BackgroundPrivacy {
        get { self[PrivacyModeKey.self] }
        set { self[PrivacyModeKey.self] = newValue }
    }
}

/// Blurs a tab's pages in the background (Blur Pictures and Text); the tab bar stays sharp.
struct PrivacyContentBlur: ViewModifier {
    @Environment(\.privacyMode) private var mode

    func body(content: Content) -> some View {
        content.blur(radius: mode == .blurContent ? 30 : 0)
    }
}

extension View {
    func privacyContentBlur() -> some View { modifier(PrivacyContentBlur()) }
}
