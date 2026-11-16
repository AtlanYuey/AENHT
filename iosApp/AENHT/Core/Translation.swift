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
        if trimmed.isEmpty { Keychain.delete("llm_\(service.id)") } else { Keychain.write("llm_\(service.id)", trimmed) }
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
    func test(_ service: LlmService, key: String, text: String) async throws -> LlmTestResult {
        do {
            let result = try await translator.test(service: service, key: key, text: text, target: target)
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
        // A service without a key counts too (self-hosted ones need none).
        services.contains { $0.enabled } || !microsoftKey.isEmpty
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
    /// When set, the translation goes here (a title shows it in place of
    /// the original) and this line shows only who translated it.
    var output: Binding<Translation?>? = nil

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
                    if output == nil {
                        Text(result.text).font(kind == .title ? .subheadline.weight(.medium) : .subheadline).privacyText()
                    }
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
            .onChange(of: result?.text) { output?.wrappedValue = result }
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
                Button("Add AI Service", systemImage: "plus.circle.fill") { adding = true }
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
                TextField("API Key", text: $msKey)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
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
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $adding) {
            NavigationStack { LlmPresetPicker() }
        }
    }

    private func serviceRow(_ s: LlmService) -> some View {
        let usage = service.usage(s)
        return HStack(spacing: 12) {
            ProviderIcon(preset: s.presetKind)
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

/// A language model service's logo (LobeHub Icons, as in AY Translate).
struct ProviderIcon: View {
    let preset: LlmPreset
    var size: CGFloat = 30

    var body: some View {
        Group {
            if preset == .other {
                Image(systemName: "slider.horizontal.3").resizable().scaledToFit().foregroundStyle(.blue).padding(size * 0.2)
            } else {
                Image("provider-" + preset.key).renderingMode(.original).resizable().scaledToFit().padding(size * 0.1)
            }
        }
        .frame(width: size, height: size)
        .background(.white, in: RoundedRectangle(cornerRadius: size * 0.27, style: .continuous))
        .accessibilityHidden(true)
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
                HStack(spacing: 12) {
                    ProviderIcon(preset: preset)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(preset == .other ? String(localized: "Other (OpenAI-compatible)") : preset.displayName)
                        Text(preset.baseUrl).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
        }
        .navigationTitle("Choose Provider")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }
}

/// A service's settings, as in AY Translate: details and key, model (from
/// the service's list), request parameters, a test, cost and limit.
struct LlmServiceEditor: View {
    let original: LlmService
    var isNew = false
    var onSaved: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var service = TranslationService.shared
    @State private var name = ""
    @State private var baseURL = ""
    @State private var key = ""
    @State private var loadedKey = ""
    @State private var loadedBaseURL = ""
    @State private var model = ""
    @State private var currency = "USD"
    @State private var inputPrice: Double?
    @State private var outputPrice: Double?
    @State private var limitOn = false
    @State private var limit: Double = 1
    @State private var parameterPreset = LlmParameterPreset.modelDefault.key
    @State private var bodyJSON = ""
    @State private var models: [String] = []
    @State private var modelSelection = otherModel
    @State private var discovering = false
    @State private var discoveryError: String?
    @State private var discoveryRevision = 0
    @State private var probeID = ""
    @State private var testing = false
    @State private var testTask: Task<Void, Never>?
    @State private var testResult: String?
    @State private var priceNote: LocalizedStringKey?
    @State private var loaded = false

    private static let otherModel = "__other__"

    private var draft: LlmService {
        original.with(
            name: name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? model : name.trimmingCharacters(in: .whitespacesAndNewlines),
            baseUrl: baseURL.trimmingCharacters(in: .whitespacesAndNewlines), model: model.trimmingCharacters(in: .whitespacesAndNewlines),
            enabled: original.enabled,
            inputPrice: inputPrice.map { KotlinDouble(value: $0) }, outputPrice: outputPrice.map { KotlinDouble(value: $0) },
            currency: currency, spendLimit: limitOn ? KotlinDouble(value: limit) : nil
        ).withRequestOptions(preset: parameterPreset, bodyJson: bodyJSON)
    }

    private var trimmedKey: String { key.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !baseURL.isEmpty && !model.isEmpty && draft.parametersValid }
    private var probes: [TranslationProbe] { TranslationProbe.companion.samples(excludingTarget: service.target) }
    private var probe: TranslationProbe { probes.first { $0.id == probeID } ?? probes[0] }
    private var discoveryIdentity: String { baseURL + "\n" + key + "\n" + String(discoveryRevision) }
    private var priceIdentity: String { [baseURL, model, loaded ? "1" : "0"].joined(separator: "\n") }

    var body: some View {
        form
            .onChange(of: modelSelection) { _, value in
                if value != Self.otherModel { model = value }
            }
            .onChange(of: baseURL) { _, new in
                models = []
                modelSelection = Self.otherModel
                discoveryError = nil
                // A saved key isn't sent to an edited address by itself.
                if new != loadedBaseURL, key == loadedKey { key = "" }
                cancelTest()
            }
            .onChange(of: key) { cancelTest() }
            .onChange(of: model) { cancelTest() }
            .onChange(of: parameterPreset) { cancelTest() }
            .onChange(of: bodyJSON) { cancelTest() }
            .onChange(of: probeID) { cancelTest() }
            .onDisappear { cancelTest() }
            .task(id: priceIdentity) {
                // Services that publish prices fill these in themselves, every
                // time this page opens (a discount shows up here).
                guard loaded, !model.isEmpty else { return }
                try? await Task.sleep(for: .milliseconds(900))
                guard !Task.isCancelled else { return }
                if let price = await service.publishedPrice(baseURL: baseURL, key: trimmedKey, model: model) {
                    inputPrice = price.input
                    outputPrice = price.output
                    priceNote = "Price provided by the service and kept up to date automatically."
                } else if !Task.isCancelled {
                    priceNote = "This provider does not publish prices. Enter the price from its pricing page; update it when it changes."
                }
            }
    }

    private var form: some View {
        Form {
            Section("Service Details") {
                TextField("Service Name", text: $name)
                LabeledContent("Provider") {
                    HStack {
                        ProviderIcon(preset: original.presetKind, size: 24)
                        Text(original.presetKind == .other ? String(localized: "Other (OpenAI-compatible)") : original.presetKind.displayName)
                    }
                }
                TextField("Base URL", text: $baseURL, axis: .vertical)
                    .keyboardType(.URL)
                // Shown as typed, so a pasted key can be checked.
                TextField("API Key (optional for keyless services)", text: $key, axis: .vertical)
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Section {
                if !models.isEmpty {
                    Picker("Model", selection: $modelSelection) {
                        ForEach(models, id: \.self) { Text(verbatim: $0).tag($0) }
                        Text("Other Model").tag(Self.otherModel)
                    }
                }
                if modelSelection == Self.otherModel || models.isEmpty {
                    TextField("Model Name", text: $model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Button { discoveryRevision += 1 } label: {
                    HStack {
                        Label("Fetch Available Models", systemImage: "arrow.clockwise")
                        Spacer()
                        if discovering { ProgressView() }
                    }
                }
                .disabled(discovering)
                if let discoveryError { Text(discoveryError).font(.footnote).foregroundStyle(.secondary) }
            } header: {
                Text("Model")
            } footer: {
                Text("Pasting a key fetches the provider’s model list. If listing is unavailable, choose Other Model and enter its ID.")
            }
            Section {
                NavigationLink {
                    LlmRequestOptionsView(preset: $parameterPreset, bodyJSON: $bodyJSON, draft: { draft })
                } label: {
                    LabeledContent("Request Parameters", value: String(localized: String.LocalizationValue(LlmParameterPreset.companion.of(key: parameterPreset).displayName)))
                }
            }
            Section {
                Picker("Test Phrase", selection: Binding(get: { probe.id }, set: { probeID = $0 })) {
                    ForEach(probes, id: \.id) { Text(verbatim: $0.languageName).tag($0.id) }
                }
                Text(verbatim: probe.text).textSelection(.enabled)
                LabeledContent("Target Language", value: Locale.current.localizedString(forIdentifier: service.target) ?? service.target)
                if let testResult { Text(verbatim: testResult).textSelection(.enabled) }
                Button(action: test) {
                    HStack {
                        Label("Test Translation", systemImage: "play.fill")
                        Spacer()
                        if testing { ProgressView() }
                    }
                }
                .disabled(testing || !canSave)
            } header: {
                Text("Test Translation")
            } footer: {
                Text("Test sends only the selected sample to this endpoint with the current key and model. Provider fees may apply. Saving alone sends no translation request.")
            }
            costSection
            Section {
                Text("The key stays visible for checking pasted text. It is saved only in this device’s Keychain, separately for each service.")
                Text("Changing the endpoint clears a previously saved key. Paste a replacement for the new destination.")
            } header: {
                Text("Device Keychain")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            if !isNew {
                Section {
                    Button("Delete Service", role: .destructive) {
                        service.remove(original)
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(isNew ? "Add AI Service" : "Edit AI Service")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    service.store(draft, key: trimmedKey)
                    if let onSaved { onSaved() } else { dismiss() }
                }
                .disabled(!canSave)
            }
        }
        .onAppear(perform: load)
        // Pasting a key (or tapping Fetch) loads the service's model list.
        .task(id: discoveryIdentity) {
            discovering = false
            guard loaded, !trimmedKey.isEmpty || discoveryRevision > 0, !baseURL.isEmpty else { return }
            let identity = discoveryIdentity
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled, identity == discoveryIdentity else { return }
            discovering = true
            discoveryError = nil
            do {
                let available = try await service.models(baseURL: baseURL.trimmingCharacters(in: .whitespacesAndNewlines), key: trimmedKey)
                guard !Task.isCancelled, identity == discoveryIdentity else { return }
                models = available
                if model.isEmpty, let first = available.first { model = first }
                modelSelection = available.contains(model) ? model : Self.otherModel
            } catch {
                if identity == discoveryIdentity { discoveryError = error.localizedDescription }
            }
            if identity == discoveryIdentity { discovering = false }
        }
    }

    @ViewBuilder private var costSection: some View {
        let usage = service.usage(original)
        let symbol = currency == "CNY" ? "¥" : "$"
        Section {
            if original.presetKind.choosesCurrency {
                Picker("Billing Currency", selection: $currency) {
                    Text(verbatim: "¥ CNY").tag("CNY")
                    Text(verbatim: "$ USD").tag("USD")
                }
            }
            LabeledContent("Input price / 1M tokens") {
                TextField("0", value: $inputPrice, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
            }
            LabeledContent("Output price / 1M tokens") {
                TextField("0", value: $outputPrice, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
            }
            if let priceNote { Text(priceNote).font(.footnote).foregroundStyle(.secondary) }
            LabeledContent("This month") { usageFigures(input: usage.monthInput, output: usage.monthOutput, cost: usage.monthCost, symbol: symbol) }
            LabeledContent("Total") { usageFigures(input: usage.totalInput, output: usage.totalOutput, cost: usage.totalCost, symbol: symbol) }
            Toggle("Monthly Spending Limit", isOn: $limitOn)
            if limitOn {
                LabeledContent("Limit") {
                    HStack(spacing: 4) {
                        Text(verbatim: symbol)
                        TextField("1", value: $limit, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    }
                }
            }
            if usage.totalInput + usage.totalOutput > 0 {
                Button("Reset Usage Figures", role: .destructive) { service.resetUsage(original) }
            }
        } header: {
            Text("Cost")
        } footer: {
            Text("Tokens are counted from what the provider reports for each request, and costed at the price in effect at that moment. When this month’s spending reaches the limit, this service is skipped: the next one in the order takes over, then the on-device engine. A limit needs a price.")
        }
    }

    /// Input and output tokens on their own lines, the amount below them.
    private func usageFigures(input: Int64, output: Int64, cost: Double, symbol: String) -> some View {
        VStack(alignment: .trailing, spacing: 2) {
            Text("Input \(Int(input).formatted()) tokens")
            Text("Output \(Int(output).formatted()) tokens")
            if inputPrice != nil || cost > 0 {
                Text(verbatim: symbol + cost.formatted(.number.precision(.fractionLength(0...4)))).foregroundStyle(.primary)
            }
        }
        .font(.callout.monospacedDigit())
    }

    private func load() {
        guard !loaded else { return }
        name = original.name
        baseURL = original.baseUrl
        loadedBaseURL = original.baseUrl
        model = original.model
        currency = original.currency
        inputPrice = original.inputPrice?.doubleValue
        outputPrice = original.outputPrice?.doubleValue
        limitOn = original.spendLimit != nil
        limit = original.spendLimit?.doubleValue ?? 1
        parameterPreset = original.parameterPreset
        bodyJSON = original.bodyJson
        loadedKey = service.key(original)
        key = loadedKey
        probeID = probes.first?.id ?? ""
        loaded = true
    }

    private func cancelTest() {
        testTask?.cancel()
        testTask = nil
        testing = false
        testResult = nil
    }

    private func test() {
        cancelTest()
        let service = self.service, draft = self.draft, key = trimmedKey, text = probe.text
        testing = true
        testTask = Task {
            do {
                let r = try await service.test(draft, key: key, text: text)
                guard !Task.isCancelled else { return }
                testResult = "\(r.text)\n\(String(format: "%.1f", Double(r.millis) / 1000)) s · \(LlmFormat.tokens(r.inputTokens + r.outputTokens))"
            } catch {
                guard !Task.isCancelled else { return }
                testResult = error.localizedDescription
            }
            testing = false
        }
    }
}

/// A service's request parameters, as in AY Translate: a preset, custom body
/// fields over it, and what is actually sent.
struct LlmRequestOptionsView: View {
    @Binding var preset: String
    @Binding var bodyJSON: String
    let draft: () -> LlmService

    var body: some View {
        Form {
            Section {
                Picker("Parameter Preset", selection: $preset) {
                    ForEach(LlmParameterPreset.entries, id: \.key) {
                        Text(String(localized: String.LocalizationValue($0.displayName))).tag($0.key)
                    }
                }
            } footer: {
                Text("Model defaults sends no reasoning override. Choose only parameters supported by your model; stronger reasoning can increase latency. Gemini's compatible API also accepts reasoning_effort on supported models.")
            }
            Section {
                TextField("JSON object", text: $bodyJSON, axis: .vertical)
                    .font(.system(.body, design: .monospaced))
                    .lineLimit(6...16)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("Custom Body Parameters")
            } footer: {
                Text("Custom values override the preset. For example: {\"temperature\":0.2,\"max_completion_tokens\":4096}. Do not put credentials here. Test Translation uses these same parameters.")
            }
            Section("Effective Parameters") {
                let text = draft().effectiveParametersText()
                Text(verbatim: draft().parametersValid ? text : String(localized: String.LocalizationValue(text)))
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }
        }
        .navigationTitle("Request Parameters")
        .navigationBarTitleDisplayMode(.inline)
    }
}
