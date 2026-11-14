import LocalAuthentication
import Observation
import SwiftUI

/// App lock: with Settings › General › App Lock on, the app asks for Face ID
/// / Touch ID (or the passcode) when it opens or comes back, and is covered
/// in the app switcher. Kept on this device only (not synced).
@MainActor
@Observable
final class AppLock {
    static let shared = AppLock()

    var isEnabled: Bool {
        didSet { UserDefaults.standard.set(isEnabled, forKey: "appLock") }
    }
    private(set) var locked: Bool
    @ObservationIgnored private var prompting = false

    private init() {
        let on = UserDefaults.standard.bool(forKey: "appLock")
        isEnabled = on
        locked = on
    }

    /// The device has a passcode (otherwise there is nothing to ask for).
    var available: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func onBackground() {
        if isEnabled { locked = true }
    }

    func unlock() {
        guard locked, !prompting else { return }
        guard available else {
            locked = false
            return
        }
        prompting = true
        let context = LAContext()
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: String(localized: "Unlock AENHT")) { ok, _ in
            Task { @MainActor in
                self.prompting = false
                if ok { self.locked = false }
            }
        }
    }
}

/// Covers the app while it is locked, or in the app switcher.
struct LockCover: View {
    let showsButton: Bool
    let unlock: () -> Void

    var body: some View {
        ZStack {
            Rectangle().fill(.background)
            VStack(spacing: 14) {
                Image(systemName: "lock.fill").font(.system(size: 40)).foregroundStyle(.secondary)
                Text(verbatim: "AENHT").font(.title2.bold())
                if showsButton {
                    Button("Unlock", action: unlock).buttonStyle(.borderedProminent)
                }
            }
        }
        .ignoresSafeArea()
    }
}
