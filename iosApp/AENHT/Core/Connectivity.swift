import Foundation
import Network
import Observation
import SwiftUI

/// Whether the device is online. [restored] counts the times the network
/// came back: what failed meanwhile (pictures, lists, pages) is tried again
/// on its own, without a tap on Retry.
@MainActor
@Observable
final class Connectivity {
    static let shared = Connectivity()

    private(set) var isOnline = true
    private(set) var restored = 0

    @ObservationIgnored private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { path in
            let online = path.status == .satisfied
            Task { @MainActor in Connectivity.shared.update(online) }
        }
        monitor.start(queue: DispatchQueue(label: "com.atlanyuey.aenht.connectivity"))
    }

    /// Starts watching (at launch).
    func start() {}

    private func update(_ online: Bool) {
        guard online != isOnline else { return }
        isOnline = online
        guard online else { return }
        // A moment for the new connection (its addresses) before trying again.
        Task {
            try? await Task.sleep(for: .seconds(1))
            if isOnline { restored += 1 }
        }
    }
}

extension View {
    /// While this view shows (an error with a retry button), [retry] runs
    /// when the network comes back.
    func retriesWhenOnline(_ retry: @escaping () async -> Void) -> some View {
        onChange(of: Connectivity.shared.restored) { Task { await retry() } }
    }
}
