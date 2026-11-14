import CoreML
import CryptoKit
import Observation
import SwiftUI
import UIKit

// Super-resolution in the reader: low-resolution pages are enlarged to the
// screen's resolution with Real-ESRGAN (anime models, BSD-3-Clause), on
// the device. The page shows at once and turns sharper when it's ready.

/// When the reader sharpens pages (Reading settings › Display).
enum SuperResolutionMode: String, CaseIterable, Identifiable {
    case off, auto, always

    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: String(localized: "Off")
        case .auto: String(localized: "Automatic")
        case .always: String(localized: "Always")
        }
    }

    /// How much larger than the page the screen must be (nil: never).
    var threshold: CGFloat? {
        switch self {
        case .off: nil
        case .auto: 1.25
        case .always: 1.02
        }
    }
}

/// The model: Standard comes with the app, High Quality is downloaded.
enum SuperResolutionModel: String, CaseIterable, Identifiable {
    case standard, high

    var id: String { rawValue }
    var title: String {
        switch self {
        case .standard: String(localized: "Standard")
        case .high: String(localized: "High Quality")
        }
    }
}

/// The screen the reader is on, in pixels (pages are enlarged to fit it).
@MainActor
enum ReaderScreen {
    static var pixels = CGSize(width: 1179, height: 2556)
}

/// Whether this device is fast enough to sharpen the pages around the one
/// shown too, measured on the first pages (no list of models).
@MainActor
enum SuperResolutionSpeed {
    /// Seconds per 256 × 256 tile, a running average; nil until measured.
    static var tileSeconds: Double?
    /// About a second for a typical page (some 25 tiles) at most.
    static var isFast: Bool { (tileSeconds ?? .infinity) < 0.045 }
}

// MARK: - Downloaded model

/// The High Quality model: downloaded, checked and compiled on request.
@MainActor
@Observable
final class SuperResolutionModels {
    static let shared = SuperResolutionModels()

    enum State: Equatable {
        case absent
        case downloading(Double)
        case installing
        case ready
        case failed(String)
    }

    private(set) var state: State = .absent

    /// The model files are in the project's repository, at a fixed commit.
    nonisolated static let packageURL = URL(string: "https://raw.githubusercontent.com/AtlanYuey/AENHT/e729c7862f525c274b4ee5f0721620cf792acdde/models/sr/AnimeSRPlus-coreml.tar")!
    nonisolated static let packageSHA256 = "a2095d57403681abc6a383dba91a8987dcafc30b499de98be1d257a63c83801b"
    nonisolated static let packageSize: Int64 = 9_144_320
    nonisolated private static let packageName = "AnimeSRPlus.mlpackage"

    nonisolated static var directory: URL {
        URL.applicationSupportDirectory.appending(path: "SuperResolution", directoryHint: .isDirectory)
    }

    nonisolated static var compiledHigh: URL {
        directory.appending(path: "AnimeSRPlus.mlmodelc", directoryHint: .isDirectory)
    }

    private var observation: NSKeyValueObservation?
    private var task: Task<Void, Never>?

    private init() {
        if FileManager.default.fileExists(atPath: Self.compiledHigh.path) { state = .ready }
    }

    var isReady: Bool { state == .ready }

    func download() {
        guard task == nil, state != .ready else { return }
        state = .downloading(0)
        task = Task {
            do {
                try await install()
                state = .ready
            } catch {
                state = .failed(error.localizedDescription)
            }
            task = nil
        }
    }

    func cancel() {
        task?.cancel()
    }

    func delete() {
        task?.cancel()
        try? FileManager.default.removeItem(at: Self.compiledHigh)
        state = .absent
        if AppSettings.shared.superResolutionModel == .high { AppSettings.shared.superResolutionModel = .standard }
        Task { await SuperResolver.shared.forget(.high) }
    }

    private func install() async throws {
        let fm = FileManager.default
        try fm.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        let archive = Self.directory.appending(path: "download.tar")
        try? fm.removeItem(at: archive)
        defer { try? fm.removeItem(at: archive) }

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let download = URLSession.shared.downloadTask(with: Self.packageURL) { file, response, error in
                    if let file, (response as? HTTPURLResponse)?.statusCode == 200 {
                        do {
                            try FileManager.default.moveItem(at: file, to: archive)
                            continuation.resume()
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume(throwing: error ?? URLError(.badServerResponse))
                    }
                }
                observation = download.progress.observe(\.fractionCompleted) { progress, _ in
                    let fraction = progress.fractionCompleted
                    Task { @MainActor in
                        if case .downloading = self.state { self.state = .downloading(fraction) }
                    }
                }
                downloadTask = download
                download.resume()
            }
        } onCancel: {
            Task { @MainActor in self.downloadTask?.cancel() }
        }
        observation = nil
        downloadTask = nil
        try Task.checkCancellation()
        state = .installing

        let compiled = try await Task.detached(priority: .userInitiated) {
            let data = try Data(contentsOf: archive, options: .mappedIfSafe)
            let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard digest == Self.packageSHA256 else { throw CocoaError(.fileReadCorruptFile) }
            let unpacked = Self.directory.appending(path: "unpacked", directoryHint: .isDirectory)
            try? FileManager.default.removeItem(at: unpacked)
            defer { try? FileManager.default.removeItem(at: unpacked) }
            try TarReader.extract(data, under: Self.packageName, to: unpacked)
            return try await MLModel.compileModel(at: unpacked.appending(path: Self.packageName))
        }.value
        try? fm.removeItem(at: Self.compiledHigh)
        try fm.moveItem(at: compiled, to: Self.compiledHigh)
    }

    private var downloadTask: URLSessionDownloadTask?
}

/// Reads a plain (ustar) tar archive: files under one folder only.
enum TarReader {
    static func extract(_ data: Data, under root: String, to destination: URL) throws {
        let fm = FileManager.default
        var offset = 0
        func text(_ range: Range<Int>) -> String {
            let bytes = data[(offset + range.lowerBound)..<(offset + range.upperBound)]
            return String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
        while offset + 512 <= data.count {
            let name = text(0..<100)
            if name.isEmpty { break }
            let prefix = text(345..<500)
            let path = prefix.isEmpty ? name : prefix + "/" + name
            let size = Int(text(124..<136).trimmingCharacters(in: .whitespaces), radix: 8) ?? 0
            let type = data[offset + 156]
            let parts = path.split(separator: "/").map(String.init)
            guard parts.first == root, !parts.contains(".."), !path.hasPrefix("/") else {
                throw CocoaError(.fileReadCorruptFile)
            }
            let target = parts.reduce(destination) { $0.appending(path: $1) }
            offset += 512
            if type == UInt8(ascii: "5") {
                try fm.createDirectory(at: target, withIntermediateDirectories: true)
            } else if type == UInt8(ascii: "0") || type == 0 {
                guard offset + size <= data.count else { throw CocoaError(.fileReadCorruptFile) }
                try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data[offset..<(offset + size)].write(to: target)
            }
            offset += (size + 511) / 512 * 512
        }
    }
}

// MARK: - Inference

/// Enlarges pages with the model, in tiles, to the size they show at.
actor SuperResolver {
    static let shared = SuperResolver()

    private static let tile = 256
    /// Context around each tile, cut off again (no seams).
    private static let pad = 12

    private var models: [SuperResolutionModel: MLModel] = [:]
    /// The first run of a model includes its preparation: not timed.
    private var warmed: Set<SuperResolutionModel> = []
    private var writes = 0
    private let memory = NSCache<NSString, UIImage>()

    private init() {
        memory.countLimit = 6
    }

    func forget(_ kind: SuperResolutionModel) {
        models[kind] = nil
        warmed.remove(kind)
    }

    private func model(_ kind: SuperResolutionModel) throws -> MLModel {
        if let model = models[kind] { return model }
        let url: URL?
        switch kind {
        case .standard: url = Bundle.main.url(forResource: "AnimeSR", withExtension: "mlmodelc")
        case .high:
            url = FileManager.default.fileExists(atPath: SuperResolutionModels.compiledHigh.path)
                ? SuperResolutionModels.compiledHigh : nil
        }
        guard let url else { throw CocoaError(.fileNoSuchFile) }
        let configuration = MLModelConfiguration()
        configuration.computeUnits = .all
        let model = try MLModel(contentsOf: url, configuration: configuration)
        models[kind] = model
        return model
    }

    private static var cacheDirectory: URL {
        URL.cachesDirectory.appending(path: "SuperResolution", directoryHint: .isDirectory)
    }

    /// The page enlarged to fit [target] (pixels), or nil when it's sharp
    /// enough already (or super-resolution is off).
    func enhance(_ image: UIImage, key: String, target: CGSize,
                 mode: SuperResolutionMode, kind: SuperResolutionModel) async -> UIImage? {
        guard let threshold = mode.threshold, let cgImage = image.cgImage else { return nil }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 16, height > 16 else { return nil }
        let fit = min(target.width / CGFloat(width), target.height / CGFloat(height))
        guard fit >= threshold else { return nil }
        // At most 4× (the model's), and at most 24 megapixels.
        var scale = min(fit, 4)
        scale = min(scale, sqrt(24_000_000 / CGFloat(width * height)))
        guard scale >= threshold else { return nil }
        let outWidth = Int((CGFloat(width) * scale).rounded())
        let outHeight = Int((CGFloat(height) * scale).rounded())

        let name = Self.cacheName(key: key, kind: kind, width: outWidth)
        if let cached = memory.object(forKey: name as NSString) { return cached }
        let file = Self.cacheDirectory.appending(path: name)
        if let data = try? Data(contentsOf: file), let cached = UIImage(data: data) {
            memory.setObject(cached, forKey: name as NSString)
            return cached
        }
        do {
            let model = try model(kind)
            let result = try await run(model, kind: kind, image: cgImage, scale: scale,
                                       outWidth: outWidth, outHeight: outHeight)
            let enhanced = UIImage(cgImage: result, scale: 1, orientation: .up)
            memory.setObject(enhanced, forKey: name as NSString)
            save(enhanced, to: file)
            return enhanced
        } catch {
            return nil
        }
    }

    private static func cacheName(key: String, kind: SuperResolutionModel, width: Int) -> String {
        let digest = SHA256.hash(data: Data("\(key)|\(kind.rawValue)|\(width)".utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined() + ".jpg"
    }

    private func save(_ image: UIImage, to file: URL) {
        guard let data = image.jpegData(compressionQuality: 0.92) else { return }
        try? FileManager.default.createDirectory(at: Self.cacheDirectory, withIntermediateDirectories: true)
        try? data.write(to: file)
        writes += 1
        if writes % 20 == 1 { Self.prune(limit: 300_000_000) }
    }

    /// Keeps the newest results within [limit] bytes.
    private static func prune(limit: Int) {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: keys) else { return }
        let entries = files.compactMap { url -> (URL, Date, Int)? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, values.contentModificationDate ?? .distantPast, values.fileSize ?? 0)
        }.sorted { $0.1 > $1.1 }
        var total = 0
        for (url, _, size) in entries {
            total += size
            if total > limit { try? FileManager.default.removeItem(at: url) }
        }
    }

    static func clearCache() {
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    private func run(_ model: MLModel, kind: SuperResolutionModel, image: CGImage, scale: CGFloat,
                     outWidth: Int, outHeight: Int) async throws -> CGImage {
        let width = image.width
        let height = image.height
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let bitmap = CGImageAlphaInfo.premultipliedLast.rawValue

        // The page's pixels (RGBA, on white).
        var source = [UInt8](repeating: 255, count: width * height * 4)
        source.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: space, bitmapInfo: bitmap) else { return }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard let output = CGContext(data: nil, width: outWidth, height: outHeight, bitsPerComponent: 8,
                                     bytesPerRow: 0, space: space, bitmapInfo: bitmap) else {
            throw CocoaError(.coderInvalidValue)
        }
        output.interpolationQuality = .high

        let tile = Self.tile
        let pad = Self.pad
        let core = tile - 2 * pad
        let input = try MLMultiArray(shape: [1, 3, NSNumber(value: tile), NSNumber(value: tile)], dataType: .float32)
        let plane = tile * tile

        var y0 = 0
        while y0 < height {
            var x0 = 0
            while x0 < width {
                try Task.checkCancellation()
                let coreWidth = min(core, width - x0)
                let coreHeight = min(core, height - y0)

                // The tile and its context; past the page's edges, its edge pixels.
                input.withUnsafeMutableBufferPointer(ofType: Float.self) { values, _ in
                    source.withUnsafeBufferPointer { pixels in
                        for ty in 0..<tile {
                            let sy = min(max(y0 - pad + ty, 0), height - 1)
                            for tx in 0..<tile {
                                let sx = min(max(x0 - pad + tx, 0), width - 1)
                                let p = (sy * width + sx) * 4
                                let i = ty * tile + tx
                                values[i] = Float(pixels[p]) / 255
                                values[plane + i] = Float(pixels[p + 1]) / 255
                                values[2 * plane + i] = Float(pixels[p + 2]) / 255
                            }
                        }
                    }
                }

                let started = CACurrentMediaTime()
                let features = try MLDictionaryFeatureProvider(dictionary: ["input": MLFeatureValue(multiArray: input)])
                let prediction = try await model.prediction(from: features)
                await record(CACurrentMediaTime() - started, kind: kind)
                guard let result = prediction.featureValue(for: "output")?.multiArrayValue else {
                    throw CocoaError(.coderInvalidValue)
                }
                let piece = try Self.image(from: result, x: 4 * pad, y: 4 * pad,
                                           width: 4 * coreWidth, height: 4 * coreHeight, space: space)
                // Whole pixels, so neighbouring tiles meet exactly.
                let left = (CGFloat(x0) * scale).rounded()
                let right = (CGFloat(x0 + coreWidth) * scale).rounded()
                let top = (CGFloat(y0) * scale).rounded()
                let bottom = (CGFloat(y0 + coreHeight) * scale).rounded()
                output.draw(piece, in: CGRect(x: left, y: CGFloat(outHeight) - bottom,
                                              width: right - left, height: bottom - top))
                x0 += core
            }
            y0 += core
        }
        guard let image = output.makeImage() else { throw CocoaError(.coderInvalidValue) }
        return image
    }

    private func record(_ seconds: Double, kind: SuperResolutionModel) async {
        guard warmed.contains(kind) else {
            warmed.insert(kind)
            return
        }
        // The Standard model is the one the speed is judged by.
        guard kind == .standard else { return }
        await MainActor.run {
            let previous = SuperResolutionSpeed.tileSeconds
            SuperResolutionSpeed.tileSeconds = previous.map { $0 * 0.8 + seconds * 0.2 } ?? seconds
        }
    }

    /// Part of the model's output (1 × 3 × H × W, RGB 0–1) as an image.
    private static func image(from array: MLMultiArray, x: Int, y: Int, width: Int, height: Int,
                              space: CGColorSpace) throws -> CGImage {
        let strides = array.strides.map(\.intValue)
        let channel = strides[1]
        let row = strides[2]
        let column = strides[3]
        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        func fill<T: BinaryFloatingPoint>(_ values: UnsafeBufferPointer<T>) {
            for py in 0..<height {
                let base = (y + py) * row
                for px in 0..<width {
                    let i = base + (x + px) * column
                    let o = (py * width + px) * 4
                    for c in 0..<3 {
                        let v = Float(values[i + c * channel])
                        pixels[o + c] = UInt8(max(0, min(255, v * 255 + 0.5)))
                    }
                }
            }
        }
        switch array.dataType {
        case .float32:
            array.withUnsafeBufferPointer(ofType: Float.self) { fill($0) }
        #if arch(arm64)
        case .float16:
            array.withUnsafeBufferPointer(ofType: Float16.self) { fill($0) }
        #endif
        default:
            throw CocoaError(.coderInvalidValue)
        }
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: width * 4, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        else { throw CocoaError(.coderInvalidValue) }
        return image
    }
}

// MARK: - Settings

/// Reading settings › Display › Super Resolution.
struct SuperResolutionSection: View {
    @Bindable var settings: AppSettings
    @State private var models = SuperResolutionModels.shared

    var body: some View {
        Section {
            Picker("Super Resolution", selection: $settings.superResolution) {
                ForEach(SuperResolutionMode.allCases) { Text($0.title).tag($0) }
            }
            if settings.superResolution != .off {
                if models.isReady {
                    Picker("Model", selection: $settings.superResolutionModel) {
                        ForEach(SuperResolutionModel.allCases) { Text($0.title).tag($0) }
                    }
                }
                highQualityRow
            }
        } header: {
            Text("Super Resolution")
        } footer: {
            Text("Sharpens low-resolution pages to your screen's resolution, on this device. A page shows at once and turns sharper when it's ready. Automatic: only pages clearly smaller than the screen; on slower devices, only the page you're on.")
        }
    }

    @ViewBuilder private var highQualityRow: some View {
        switch models.state {
        case .absent, .failed:
            Button {
                models.download()
            } label: {
                LabeledContent {
                    Text(ByteCountFormatter.string(fromByteCount: SuperResolutionModels.packageSize, countStyle: .file))
                } label: {
                    Label("Download High Quality Model", systemImage: "arrow.down.circle")
                }
            }
            if case let .failed(message) = models.state {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
        case let .downloading(fraction):
            HStack {
                ProgressView(value: fraction)
                Button("Cancel", systemImage: "xmark.circle.fill") { models.cancel() }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
            }
        case .installing:
            HStack {
                Text("Installing…")
                Spacer()
                ProgressView()
            }
        case .ready:
            Button("Delete High Quality Model", systemImage: "trash", role: .destructive) { models.delete() }
        }
    }
}
