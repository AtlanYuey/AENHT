import Observation
import Shared
import SwiftUI

/// The sites limit how many requests may be sent: when the app has to wait
/// for its turn (a few seconds or more), it says so instead of only showing
/// a spinner.
@MainActor
@Observable
final class RateNotice {
    static let shared = RateNotice()

    /// When the request that waits goes on; nil: none waits.
    private(set) var until: Date?
    @ObservationIgnored private let listener = Listener()

    private init() {}

    func watch(_ limiter: RateLimiter) {
        limiter.watch(listener: listener)
    }

    /// Called by the shared module from the thread of the request that waits.
    private final class Listener: NSObject, RateWaitListener {
        func waiting(bucket: String, waitMillis: Int64) {
            let seconds = Double(waitMillis) / 1000
            // Shorter waits aren't worth a notice.
            guard seconds >= 2 else { return }
            Task { @MainActor in RateNotice.shared.waiting(seconds) }
        }
    }

    @ObservationIgnored private var clearing: Task<Void, Never>?

    private func waiting(_ seconds: TimeInterval) {
        let end = Date().addingTimeInterval(seconds)
        guard (until ?? .distantPast) < end else { return }
        until = end
        // Gone when the wait is over: the banner's timeline doesn't keep running.
        clearing?.cancel()
        clearing = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, let self, let until = self.until, until <= Date() else { return }
            self.until = nil
        }
    }
}

/// The notice, at the top of the screen while a request waits.
struct RateNoticeBanner: View {
    @State private var notice = RateNotice.shared

    var body: some View {
        if let until = notice.until {
            TimelineView(.periodic(from: .now, by: 0.5)) { context in
                let left = Int(until.timeIntervalSince(context.date).rounded(.up))
                if left > 0 {
                    Text("The site limits requests. Continuing in \(left) s.")
                        .font(.footnote)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                        .transition(.opacity)
                }
            }
            .allowsHitTesting(false)
        }
    }
}
