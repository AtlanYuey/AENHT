import Foundation
import Observation
import Shared
import SwiftUI

/// Translation of titles and comments, like AY Translate: the user's
/// language models in their order (a model that refuses, fails or reached
/// its monthly spending limit hands over to the next one), then Microsoft
/// Translator with the user's key, then Apple's on-device translation. Keys
/// stay in the Keychain; the services and their usage are kept with the
/// settings, without keys.
@MainActor
@Observable
final class TranslationService {
    static let shared = TranslationService()

    @ObservationIgnored private let translator = Translator()
    @ObservationIgnored private var cache: [String: Translation] = [:]
    /// Bumped when services, keys or usage change.
    private(set) var revision = 0

    private init() {
        translator.ledger = LlmUsageLedger.companion.decode(text: UserDefaults.standard.string(forKey: "llmUsage"))
    }

    var services: [LlmService] {
        _ = revision
        return Translator.companion.decodeServices(text: AppSettings.shared.llmServices)
    }

    private func save(_ services: [LlmService]) {
        AppSettings.shared.llmServices = Translator.companion.encodeServices(services: services)
        revision += 1
    }

    private func saveUsage() {
        UserDefaults.standard.set(translator.ledger.encode(), forKey: "llmUsage")
        revision += 1
    }

    /// Adds or updates a service; an empty key keeps the saved one.
    func store(_ service: LlmService, key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { Keychain.write("llm_\(service.id)", trimmed) }
        var list = services
        if let i = list.firstIndex(where: { $0.id == service.id }) { list[i] = service } else { list.append(service) }
        save(list)
    }

    func remove(_ service: LlmService) {
        Keychain.delete("llm_\(service.id)")
        save(services.filter { $0.id != service.id })
    }

    func move(from source: IndexSet, to destination: Int) {
        var list = services
        list.move(fromOffsets: source, toOffset: destination)
        save(list)
    }

    func setEnabled(_ service: LlmService, _ enabled: Bool) {
        save(services.map {
            $0.id == service.id
                ? $0.with(name: $0.name, baseUrl: $0.baseUrl, model: $0.model, enabled: enabled, inputPrice: $0.inputPrice,
                          outputPrice: $0.outputPrice, currency: $0.currency, spendLimit: $0.spendLimit)
                : $0
        })
    }

    func hasKey(_ service: LlmService) -> Bool { !(Keychain.read("llm_\(service.id)") ?? "").isEmpty }
    func key(_ service: LlmService) -> String { Keychain.read("llm_\(service.id)") ?? "" }

    func usage(_ service: LlmService) -> LlmUsage {
        _ = revision
        return translator.usage(service: service)
    }

    func overLimit(_ service: LlmService) -> Bool {
        _ = revision
        return translator.overLimit(service: service)
    }

    func resetUsage(_ service: LlmService) {
        translator.ledger.reset(id: service.id)
        saveUsage()
    }

    /// The models a service offers (its …/models list).
    func models(baseURL: String, key: String) async throws -> [String] {
        do {
            return try await translator.models(baseUrl: baseURL, key: key)
        } catch {
            throw EhError.failed(Self.message(error))
        }
    }

    /// The price a service publishes for a model (OpenRouter), per million tokens.
    func publishedPrice(baseURL: String, key: String, model: String) async -> (input: Double, output: Double)? {
        guard let p = try? await translator.publishedPrice(baseUrl: baseURL, key: key, model: model), p.count == 2 else { return nil }
        return (p[0].doubleValue, p[1].doubleValue)
    }

    /// Translates a sample with the service (its usage counts too).
    func test(_ service: LlmService, key: String) async throws -> LlmTestResult {
        let sample = target.hasPrefix("en") ? "今日はいい天気ですね。散歩に行きましょう。" : "It's a nice day today. Let's go for a walk."
        do {
            let result = try await translator.test(service: service, key: key, text: sample, target: target)
            saveUsage()
            return result
        } catch {
            throw EhError.failed(Self.message(error))
        }
    }

    private static func message(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? KotlinThrowable)?.message ?? error.localizedDescription
    }

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
            saveUsage()
            return result
        } catch {
            saveUsage()
            throw EhError.failed(Self.message(error).isEmpty ? String(localized: "Couldn't translate.") : Self.message(error))
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

/// Me › Translation: the language models in their order, Microsoft
/// Translator and the device's own translation (like AY Translate).
struct TranslationSettingsView: View {
    @State private var service = TranslationService.shared
    @Environment(AppSettings.self) private var settings
    @State private var msKey = TranslationService.shared.microsoftKey
    @State private var msRegion = TranslationService.shared.microsoftRegion
    @State private var adding = false
    @State private var editMode: EditMode = .inactive

    var body: some View {
        @Bindable var settings = settings
        Form {
            let services = service.services
            Section {
                ForEach(services, id: \.id) { s in
                    NavigationLink {
                        LlmServiceEditor(original: s)
                    } label: {
                        serviceRow(s)
                    }
                }
                .onMove { service.move(from: $0, to: $1) }
                .onDelete { offsets in offsets.map { services[$0] }.forEach(service.remove) }
                Button("Add Service", systemImage: "plus.circle.fill") { adding = true }
            } header: {
                HStack {
                    Text("Order of Use")
                    Spacer()
                    if services.count > 1 {
                        Button(editMode.isEditing ? "Done" : "Reorder") {
                            withAnimation { editMode = editMode.isEditing ? .inactive : .active }
                        }
                        .font(.footnote.weight(.semibold))
                        .textCase(nil)
                    }
                }
            } footer: {
                Text("Tried from top to bottom: a model that refuses, fails or reaches its monthly spending limit hands over to the next one, then to Microsoft Translator, then to the on-device translation.")
            }
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
                Toggle("On-Device Translation", isOn: $settings.deviceTranslation)
            } footer: {
                Text("The last fallback, free: Apple's on-device translation. The first time, the system offers to download each language.")
            }
        }
        .environment(\.editMode, $editMode)
        .navigationTitle("Translation")
        .sheet(isPresented: $adding) {
            NavigationStack { LlmPresetPicker() }
        }
    }

    private func serviceRow(_ s: LlmService) -> some View {
        let usage = service.usage(s)
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(s.name).foregroundStyle(s.enabled ? .primary : .secondary)
                Group {
                    if !service.hasKey(s) {
                        Text("\(s.model) · \(String(localized: "No key"))")
                    } else if service.overLimit(s) {
                        Text("\(s.model) · \(String(localized: "Spending limit reached"))").foregroundStyle(.orange)
                    } else if usage.monthInput + usage.monthOutput > 0 {
                        Text("\(s.model) · \(String(localized: "This month")) \(LlmFormat.cost(usage.monthCost, s)) · \(LlmFormat.tokens(usage.monthInput + usage.monthOutput))")
                    } else {
                        Text(s.model)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { s.enabled }, set: { service.setEnabled(s, $0) }))
                .labelsHidden()
        }
    }
}

enum LlmFormat {
    static func cost(_ value: Double, _ service: LlmService) -> String {
        service.currencySymbol + String(format: value < 0.01 && value > 0 ? "%.4f" : "%.2f", value)
    }

    static func tokens(_ count: Int64) -> String {
        "\(Int(count).formatted(.number.notation(.compactName))) tokens"
    }
}

/// Add Service: the presets (address and a model filled in).
private struct LlmPresetPicker: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List(LlmPreset.entries, id: \.key) { preset in
            NavigationLink {
                LlmServiceEditor(original: LlmService.companion.create(id: UUID().uuidString, preset: preset), isNew: true) { dismiss() }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(preset == .other ? String(localized: "Other (OpenAI-compatible)") : preset.displayName)
                    Text(preset.baseUrl).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .navigationTitle("Add Service")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }
}

/// A service's settings: address, key, model (from its list), test, prices,
/// monthly limit and usage.
struct LlmServiceEditor: View {
    let original: LlmService
    var isNew = false
    var onSaved: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var service = TranslationService.shared
    @State private var name = ""
    @State private var baseURL = ""
    @State private var key = ""
    @State private var model = ""
    @State private var currency = "USD"
    @State private var inputPrice: Double?
    @State private var outputPrice: Double?
    @State private var limitOn = false
    @State private var limit: Double = 1
    @State private var models: [String] = []
    @State private var loadingModels = false
    @State private var testing = false
    @State private var testResult: String?
    @State private var error: String?
    @State private var loaded = false

    private var draft: LlmService {
        original.with(
            name: name.trimmingCharacters(in: .whitespaces).isEmpty ? model : name.trimmingCharacters(in: .whitespaces),
            baseUrl: baseURL.trimmingCharacters(in: .whitespaces), model: model.trimmingCharacters(in: .whitespaces),
            enabled: original.enabled,
            inputPrice: inputPrice.map { KotlinDouble(value: $0) }, outputPrice: outputPrice.map { KotlinDouble(value: $0) },
            currency: currency, spendLimit: limitOn ? KotlinDouble(value: limit) : nil
        )
    }

    private var effectiveKey: String { key.isEmpty ? service.key(original) : key }
    private var canSave: Bool { !baseURL.isEmpty && !model.isEmpty && !effectiveKey.isEmpty }

    var body: some View {
        Form {
            Section("Service") {
                TextField("Name", text: $name)
                TextField("API Address", text: $baseURL).keyboardType(.URL)
                SecureField(service.hasKey(original) ? String(localized: "API Key (saved)") : String(localized: "API Key"), text: $key)
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Section {
                if models.isEmpty {
                    TextField("Model", text: $model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    Picker("Model", selection: $model) {
                        if !models.contains(model) { Text(model.isEmpty ? String(localized: "Choose") : model).tag(model) }
                        ForEach(models, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.navigationLink)
                }
                Button {
                    Task { await loadModels() }
                } label: {
                    HStack {
                        Text(models.isEmpty ? "Load Model List" : "Reload Model List")
                        if loadingModels { Spacer(); ProgressView() }
                    }
                }
                .disabled(loadingModels || baseURL.isEmpty || effectiveKey.isEmpty)
            } header: {
                Text("Model")
            } footer: {
                Text("The model can be typed in, or chosen from the service's own list.")
            }
            Section {
                Button {
                    Task { await test() }
                } label: {
                    HStack {
                        Text("Test Translation")
                        if testing { Spacer(); ProgressView() }
                    }
                }
                .disabled(testing || !canSave)
                if let testResult { Text(testResult).font(.callout) }
            }
            costSection
            if !isNew {
                Section {
                    Button("Delete Service", role: .destructive) {
                        service.remove(original)
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(name.isEmpty ? original.name : name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    service.store(draft, key: key)
                    if let onSaved { onSaved() } else { dismiss() }
                }
                .disabled(!canSave)
            }
        }
        .onAppear(perform: load)
        .onChange(of: model) { _, new in
            guard original.presetKind == .openrouter, !new.isEmpty else { return }
            Task {
                if let price = await service.publishedPrice(baseURL: baseURL, key: effectiveKey, model: new) {
                    inputPrice = price.input
                    outputPrice = price.output
                }
            }
        }
        .alert("Couldn't Connect", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    @ViewBuilder private var costSection: some View {
        let usage = service.usage(original)
        let symbol = currency == "CNY" ? "¥" : "$"
        Section {
            if original.presetKind.choosesCurrency {
                Picker("Billing Currency", selection: $currency) {
                    Text("US Dollar ($)").tag("USD")
                    Text("Chinese Yuan (¥)").tag("CNY")
                }
            }
            LabeledContent("Input, per 1M tokens") {
                TextField("0", value: $inputPrice, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
            }
            LabeledContent("Output, per 1M tokens") {
                TextField("0", value: $outputPrice, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
            }
            Toggle("Monthly Spending Limit", isOn: $limitOn)
            if limitOn {
                LabeledContent("Limit (\(symbol))") {
                    TextField("1", value: $limit, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }
            }
            if !isNew {
                LabeledContent("This Month", value: "\(LlmFormat.cost(usage.monthCost, draft)) · \(LlmFormat.tokens(usage.monthInput + usage.monthOutput))")
                LabeledContent("Total", value: "\(LlmFormat.cost(usage.totalCost, draft)) · \(LlmFormat.tokens(usage.totalInput + usage.totalOutput))")
                Button("Reset Usage Figures", role: .destructive) { service.resetUsage(original) }
            }
        } header: {
            Text("Cost")
        } footer: {
            Text("Prices are per million tokens in the service's billing currency (OpenRouter publishes its own). Past the monthly limit, the next service in the order takes over.")
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        name = original.name
        baseURL = original.baseUrl
        model = original.model
        currency = original.currency
        inputPrice = original.inputPrice?.doubleValue
        outputPrice = original.outputPrice?.doubleValue
        limitOn = original.spendLimit != nil
        limit = original.spendLimit?.doubleValue ?? 1
    }

    private func loadModels() async {
        loadingModels = true
        defer { loadingModels = false }
        do {
            models = try await service.models(baseURL: baseURL.trimmingCharacters(in: .whitespaces), key: effectiveKey)
            if model.isEmpty, let first = models.first { model = first }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func test() async {
        testing = true
        defer { testing = false }
        do {
            let r = try await service.test(draft, key: effectiveKey)
            testResult = "\(r.text)\n\(String(format: "%.1f", Double(r.millis) / 1000)) s · \(LlmFormat.tokens(r.inputTokens + r.outputTokens))"
        } catch {
            testResult = nil
            self.error = error.localizedDescription
        }
    }
}
