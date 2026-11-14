import Foundation
import Observation
import Shared
import SwiftUI

/// Translation of titles and comments: the user's language models in
/// order, then Microsoft Translator with the user's key (see the shared
/// Translator), then Apple's on-device translation. Keys stay in the Keychain; the list of services is stored
/// with the settings without keys.
@MainActor
@Observable
final class TranslationService {
    static let shared = TranslationService()

    @ObservationIgnored private let translator = Translator()
    @ObservationIgnored private var cache: [String: Translation] = [:]
    /// Bumped when services or keys change.
    private(set) var revision = 0

    var services: [LlmService] {
        _ = revision
        return Translator.companion.decodeServices(text: AppSettings.shared.llmServices)
    }

    private func save(_ services: [LlmService]) {
        AppSettings.shared.llmServices = Translator.companion.encodeServices(services: services)
        revision += 1
    }

    func add(name: String, baseURL: String, model: String, key: String) {
        let id = UUID().uuidString
        let trimmedModel = model.trimmingCharacters(in: .whitespaces)
        let service = LlmService(id: id, name: name.isEmpty ? trimmedModel : name, baseUrl: baseURL.trimmingCharacters(in: .whitespaces),
                                 model: trimmedModel, enabled: true)
        Keychain.write("llm_\(id)", key.trimmingCharacters(in: .whitespaces))
        save(services + [service])
    }

    func remove(_ service: LlmService) {
        Keychain.delete("llm_\(service.id)")
        save(services.filter { $0.id != service.id })
    }

    func move(_ service: LlmService, up: Bool) {
        var list = services
        guard let i = list.firstIndex(where: { $0.id == service.id }) else { return }
        let j = up ? i - 1 : i + 1
        guard list.indices.contains(j) else { return }
        list.swapAt(i, j)
        save(list)
    }

    func setEnabled(_ service: LlmService, _ enabled: Bool) {
        save(services.map {
            $0.id == service.id ? LlmService(id: $0.id, name: $0.name, baseUrl: $0.baseUrl, model: $0.model, enabled: enabled) : $0
        })
    }

    func hasKey(_ service: LlmService) -> Bool { !(Keychain.read("llm_\(service.id)") ?? "").isEmpty }

    var microsoftKey: String {
        get { _ = revision; return Keychain.read("translator_microsoft") ?? "" }
        set {
            if newValue.isEmpty { Keychain.delete("translator_microsoft") } else { Keychain.write("translator_microsoft", newValue) }
            revision += 1
        }
    }

    var microsoftRegion: String {
        get { _ = revision; return Keychain.read("translator_microsoft_region") ?? "" }
        set {
            if newValue.isEmpty { Keychain.delete("translator_microsoft_region") } else { Keychain.write("translator_microsoft_region", newValue) }
            revision += 1
        }
    }

    /// A language model or Microsoft Translator is set up.
    var hasRemote: Bool {
        services.contains { $0.enabled && hasKey($0) } || !microsoftKey.isEmpty
    }

    var isAvailable: Bool { AppSettings.shared.deviceTranslation || hasRemote }

    func cached(_ text: String, kind: TranslationKind) -> Translation? { cache["\(target)|\(kind.name)|\(text)"] }

    func store(_ translation: Translation, for text: String, kind: TranslationKind) {
        cache["\(target)|\(kind.name)|\(text)"] = translation
    }

    /// The app's language, as the target.
    var target: String {
        let code = Locale.preferredLanguages.first ?? "en"
        if code.hasPrefix("zh") { return code.contains("Hant") || code.hasSuffix("TW") || code.hasSuffix("HK") ? "zh-Hant" : "zh-Hans" }
        return code
    }

    func translate(_ text: String, kind: TranslationKind) async throws -> Translation {
        let key = "\(target)|\(kind.name)|\(text)"
        if let cached = cache[key] { return cached }
        translator.microsoftKey = microsoftKey.isEmpty ? nil : microsoftKey
        translator.microsoftRegion = microsoftRegion.isEmpty ? nil : microsoftRegion
        let list = services
        var keys: [String: String] = [:]
        for s in list { if let k = Keychain.read("llm_\(s.id)") { keys[s.id] = k } }
        do {
            let result = try await translator.translate(text: text, target: target, kind: kind, services: list, keys: keys, local: nil)
            cache[key] = result
            return result
        } catch {
            let message = ((error as NSError).userInfo["KotlinException"] as? KotlinThrowable)?.message
            throw EhError.failed(message ?? String(localized: "Couldn't translate."))
        }
    }
}

/// "Translate" under a title or comment; tapped, the translation shows in
/// its place with who made it. Hidden until a translator is set up.
struct TranslateLine: View {
    let text: String
    let kind: TranslationKind

    @State private var result: Translation?
    @State private var error: String?
    @State private var working = false
    @State private var fellBack = false
    @State private var deviceRequest: UUID?
    private var service: TranslationService { .shared }

    var body: some View {
        if service.isAvailable {
            VStack(alignment: .leading, spacing: 4) {
                if let result {
                    Text(result.text).font(kind == .title ? .subheadline.weight(.medium) : .subheadline)
                    Text(result.fellBack ? "Translated by \(result.provider) (switched)" : "Translated by \(result.provider)")
                        .font(.caption2).foregroundStyle(.tertiary)
                } else {
                    Button(action: start) {
                        HStack(spacing: 5) {
                            if working { ProgressView().controlSize(.mini) } else { Image(systemName: "character.bubble") }
                            Text("Translate")
                        }
                        .font(.caption.weight(.medium))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tint)
                    .disabled(working)
                    if let error { Text(error).font(.caption2).foregroundStyle(.red).lineLimit(2) }
                }
            }
            .modifier(DeviceTranslationTask(text: text, target: service.target, request: $deviceRequest) { outcome in
                switch outcome {
                case .success(let translated):
                    let t = Translation(text: translated, provider: String(localized: "On-Device Translation"), fellBack: fellBack)
                    service.store(t, for: text, kind: kind)
                    result = t
                case .failure(let failure):
                    error = failure.localizedDescription
                }
                working = false
            })
        }
    }

    private func start() {
        if let cached = service.cached(text, kind: kind) { result = cached; return }
        working = true
        error = nil
        fellBack = false
        guard service.hasRemote else { deviceRequest = UUID(); return }
        Task {
            do {
                result = try await service.translate(text, kind: kind)
                working = false
            } catch {
                if AppSettings.shared.deviceTranslation {
                    fellBack = true
                    deviceRequest = UUID()
                } else {
                    self.error = error.localizedDescription
                    working = false
                }
            }
        }
    }
}

/// Me › Translation: the language models (in order) and Microsoft Translator.
struct TranslationSettingsView: View {
    @State private var service = TranslationService.shared
    @Environment(AppSettings.self) private var settings
    @State private var name = ""
    @State private var baseURL = "https://api.openai.com/v1"
    @State private var model = ""
    @State private var key = ""
    @State private var msKey = TranslationService.shared.microsoftKey
    @State private var msRegion = TranslationService.shared.microsoftRegion

    var body: some View {
        Form {
            let services = service.services
            if !services.isEmpty {
                Section {
                    ForEach(services, id: \.id) { s in
                        Toggle(isOn: Binding(get: { s.enabled }, set: { service.setEnabled(s, $0) })) {
                            VStack(alignment: .leading) {
                                Text(s.name)
                                Text(service.hasKey(s) ? s.model : "\(s.model) · \(String(localized: "No key"))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button("Delete", systemImage: "trash", role: .destructive) { service.remove(s) }
                            Button("Move Up", systemImage: "arrow.up") { service.move(s, up: true) }
                        }
                    }
                } header: {
                    Text("Language Models")
                } footer: {
                    Text("Tried from top to bottom: a model that refuses or fails hands over to the next one, then to Microsoft Translator.")
                }
            }
            Section {
                TextField("Name", text: $name)
                TextField("API Address", text: $baseURL).keyboardType(.URL)
                TextField("Model", text: $model)
                SecureField("API Key", text: $key)
                Button("Add") {
                    service.add(name: name, baseURL: baseURL, model: model, key: key)
                    name = ""; model = ""; key = ""
                }
                .disabled(baseURL.isEmpty || model.isEmpty || key.isEmpty)
            } header: {
                Text("Add a Language Model")
            } footer: {
                Text("Any service with an OpenAI-compatible API. Keys stay on this device (Keychain) and are not exported with the settings.")
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Section {
                SecureField("API Key", text: $msKey)
                TextField("Region (optional)", text: $msRegion)
                    .textInputAutocapitalization(.never)
                Button("Save") {
                    service.microsoftKey = msKey
                    service.microsoftRegion = msRegion
                }
            } header: {
                Text("Microsoft Translator")
            } footer: {
                Text("Used when no language model is set up or all of them fail. A key from Azure Translator has a free monthly allowance.")
            }
            Section {
                @Bindable var settings = settings
                Toggle("On-Device Translation", isOn: $settings.deviceTranslation)
            } footer: {
                Text("The last fallback, free: Apple's on-device translation. The first time, the system offers to download each language.")
            }
        }
        .navigationTitle("Translation")
    }
}
