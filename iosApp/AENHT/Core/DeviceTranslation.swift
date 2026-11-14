import SwiftUI
import Translation

/// Apple's on-device translation (the Translation framework): the last
/// fallback, free. Runs when `request` changes to a new value; the source
/// language is recognized, and the system offers to download a language
/// the first time.
struct DeviceTranslationTask: ViewModifier {
    let text: String
    let target: String
    @Binding var request: UUID?
    let completion: (Result<String, Error>) -> Void

    @State private var configuration: TranslationSession.Configuration?

    func body(content: Content) -> some View {
        content
            .translationTask(configuration) { session in
                do {
                    let response = try await session.translate(text)
                    await MainActor.run { completion(.success(response.targetText)) }
                } catch {
                    await MainActor.run { completion(.failure(error)) }
                }
                await MainActor.run {
                    configuration = nil
                    request = nil
                }
            }
            .onChange(of: request) { _, new in
                guard new != nil else { return }
                configuration = TranslationSession.Configuration(source: nil, target: Locale.Language(identifier: target))
            }
    }
}
