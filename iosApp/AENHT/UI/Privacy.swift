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

/// Blurs content text in the background (Blur Pictures and Text): titles,
/// tags, comments. Buttons, bars and settings stay sharp.
struct PrivacyTextBlur: ViewModifier {
    @Environment(\.privacyMode) private var mode

    func body(content: Content) -> some View {
        content.blur(radius: mode == .blurContent ? 7 : 0)
    }
}

extension View {
    func privacyText() -> some View { modifier(PrivacyTextBlur()) }
}
