import SwiftUI
import UIKit

/// Receives the Apple Pencil's double-tap and squeeze (the pencil in the
/// hand, away from the screen) while this view is on screen. The system's
/// own setting for the gesture (Settings › Apple Pencil) is followed: when
/// it is off, nothing happens.
struct PencilGestures: UIViewRepresentable {
    var onDoubleTap: () -> Void
    var onSqueeze: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        let interaction = UIPencilInteraction(delegate: context.coordinator)
        view.addInteraction(interaction)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.onDoubleTap = onDoubleTap
        context.coordinator.onSqueeze = onSqueeze
    }

    @MainActor
    final class Coordinator: NSObject, UIPencilInteractionDelegate {
        var onDoubleTap: () -> Void = {}
        var onSqueeze: () -> Void = {}

        func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveTap tap: UIPencilInteraction.Tap) {
            AppSettings.shared.notePencil(.doubleTap)
            guard UIPencilInteraction.preferredTapAction != .ignore else { return }
            onDoubleTap()
        }

        func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
            guard squeeze.phase == .ended else { return }
            AppSettings.shared.notePencil(.squeeze)
            guard UIPencilInteraction.preferredSqueezeAction != .ignore else { return }
            onSqueeze()
        }
    }
}

extension AppSettings {
    /// The pencil sent [gesture]: it is at least that pencil.
    func notePencil(_ gesture: PencilSeen) {
        if pencilSeen < gesture { pencilSeen = gesture }
    }
}

/// Apple Pencil's settings: in the reader's Controls and on Me › Apple Pencil.
struct ApplePencilSection: View {
    @Environment(AppSettings.self) private var settings
    /// A gesture just made on this page (Me › Apple Pencil), to show it arrived.
    var received: String? = nil

    var body: some View {
        @Bindable var settings = settings
        let support = PencilSupport.current ?? .unknown
        Section {
            Picker("Double-Tap", selection: $settings.pencilDoubleTap) {
                ForEach(PencilAction.allCases) { Text($0.title).tag($0) }
            }
            .disabled(!support.doubleTaps)
            if support.squeezes {
                Picker("Squeeze", selection: $settings.pencilSqueeze) {
                    ForEach(PencilAction.allCases) { Text($0.title).tag($0) }
                }
            }
        } header: {
            Text(verbatim: "Apple Pencil")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text(supportText(support))
                if settings.pencilSeen == .squeeze {
                    Text("Detected: Apple Pencil Pro (it was squeezed).")
                } else if settings.pencilSeen == .doubleTap {
                    Text("Detected: Apple Pencil (2nd generation) or Pro (it was double-tapped).")
                }
                if support.doubleTaps, UIPencilInteraction.preferredTapAction == .ignore {
                    Text("Double-tap is off in Settings › Apple Pencil, so the reader doesn't respond to it.")
                }
                if support.squeezes, UIPencilInteraction.preferredSqueezeAction == .ignore {
                    Text("Squeeze is off in Settings › Apple Pencil, so the reader doesn't respond to it.")
                }
                if let received {
                    Text(received).foregroundStyle(.tint)
                }
            }
        }
    }

    private func supportText(_ support: PencilSupport) -> LocalizedStringKey {
        switch support {
        case .squeeze:
            "This iPad works with Apple Pencil Pro: while reading, double-tap or squeeze it, held away from the screen."
        case .doubleTap:
            "This iPad works with Apple Pencil (2nd generation): while reading, double-tap it, held away from the screen. Squeezing needs Apple Pencil Pro, which this iPad doesn't work with."
        case .none:
            "The Apple Pencil this iPad works with can't double-tap or squeeze, so it can't turn pages."
        case .unknown:
            "While reading, double-tap Apple Pencil (2nd generation) or Pro, or squeeze Apple Pencil Pro, held away from the screen."
        }
    }
}

/// Me › Apple Pencil: the reader's pencil gestures, and a place to try them
/// (a gesture made here shows it arrived, and tells which pencil it is).
struct ApplePencilSettingsView: View {
    @State private var received: String?
    @State private var clear: Task<Void, Never>?

    var body: some View {
        SettingsForm {
            ApplePencilSection(received: received)
            Section {
                Text("Try it here: double-tap or squeeze the pencil.")
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle(Text(verbatim: "Apple Pencil"))
        .navigationBarTitleDisplayMode(.inline)
        .background(PencilGestures(
            onDoubleTap: { show(String(localized: "Double-tap received.")) },
            onSqueeze: { show(String(localized: "Squeeze received.")) }
        ))
    }

    private func show(_ text: String) {
        received = text
        clear?.cancel()
        clear = Task {
            try? await Task.sleep(for: .seconds(2))
            if !Task.isCancelled { received = nil }
        }
    }
}
