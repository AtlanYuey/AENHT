import GameController
import SwiftUI

/// Hardware keyboards and Bluetooth page turners (which are keyboards to
/// the system) connected now, and their keys while the reader is open.
/// Through GameController, the keys arrive whatever has the focus.
@MainActor @Observable
final class ExternalKeyboards {
    static let shared = ExternalKeyboards()

    /// The connected keyboard's name (a generic one when it has none); nil without one.
    private(set) var name: String?
    var isConnected: Bool { name != nil }

    /// The reader's key handler, while it is open.
    @ObservationIgnored var onKey: ((PageKey) -> Void)? {
        didSet { attach() }
    }

    private init() {
        NotificationCenter.default.addObserver(forName: .GCKeyboardDidConnect, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ExternalKeyboards.shared.refresh() }
        }
        NotificationCenter.default.addObserver(forName: .GCKeyboardDidDisconnect, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ExternalKeyboards.shared.refresh() }
        }
        refresh()
    }

    /// Starts watching (the first use of [shared] does).
    func start() {}

    private func refresh() {
        if let keyboard = GCKeyboard.coalesced {
            let vendor = keyboard.vendorName?.trimmingCharacters(in: .whitespaces) ?? ""
            name = vendor.isEmpty ? String(localized: "Keyboard") : vendor
        } else {
            name = nil
        }
        attach()
    }

    private func attach() {
        guard let input = GCKeyboard.coalesced?.keyboardInput else { return }
        guard onKey != nil else {
            input.keyChangedHandler = nil
            return
        }
        input.keyChangedHandler = { input, _, code, pressed in
            guard pressed else { return }
            let shift = input.button(forKeyCode: .leftShift)?.isPressed == true
                || input.button(forKeyCode: .rightShift)?.isPressed == true
            let key = ExternalKeyboards.pageKey(code, shift: shift)
            guard let key else { return }
            MainActor.assumeIsolated { ExternalKeyboards.shared.onKey?(key) }
        }
    }
}

extension ExternalKeyboards {
    /// The page key [code] is (with Shift held: [shift]), if it is one.
    nonisolated static func pageKey(_ code: GCKeyCode, shift: Bool) -> PageKey? {
        switch code {
        case .downArrow, .pageDown: return .next
        case .upArrow, .pageUp: return .previous
        case .spacebar: return shift ? .previous : .next
        case .rightArrow: return .right
        case .leftArrow: return .left
        default:
            // A Windows pen's (Surface Pen's) top button: F20 clicked, F19 double-clicked (HID usages).
            switch code.rawValue {
            case 0x6F: return .next
            case 0x6E: return .previous
            default: return nil
            }
        }
    }
}

/// The keyboard and page turner settings: in the reader's Controls, only
/// while one is connected.
struct PageKeysSection: View {
    @Environment(AppSettings.self) private var settings
    private var keyboards = ExternalKeyboards.shared

    var body: some View {
        @Bindable var settings = settings
        if let name = keyboards.name {
            Section {
                Toggle("Keys Turn Pages", isOn: $settings.pageKeys)
                if settings.pageKeys {
                    Toggle("Swap Next and Previous", isOn: $settings.pageKeysSwap)
                }
            } header: {
                Text("Keyboard and Page Turner")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Connected: \(name)")
                    Text("→ ↓, Page Down and Space turn to the next page; ← ↑ and Page Up to the previous one (← → follow the reading direction). In the continuous modes they scroll a screen. Most Bluetooth page turners send these keys; ones that send the volume buttons can't turn pages on iPhone and iPad.")
                    Text("A Windows pen paired over Bluetooth (such as Surface Pen): a click of its top button turns to the next page, a double click to the previous one.")
                }
            }
        }
    }
}
