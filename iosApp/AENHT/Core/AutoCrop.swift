import Nuke
import Shared
import UIKit

/// Reader › Auto Crop: cuts plain white / black margins off a page (the
/// shared BorderCrop finds them on a small copy).
struct AutoCropProcessor: ImageProcessing, Hashable {
    var identifier: String { "com.atlanyuey.aenht.autoCrop1" }

    /// A page request, cropped when Auto Crop is on.
    @MainActor
    static func request(_ url: URL) -> ImageRequest {
        request(url, crop: AppSettings.shared.autoCrop)
    }

    static func request(_ url: URL, crop: Bool, priority: ImageRequest.Priority = .normal) -> ImageRequest {
        guard crop else { return ImageRequest(url: url, priority: priority) }
        return ReaderStripImages.processedRequest(for: url, priority: priority, processors: [AutoCropProcessor()])
    }

    func process(_ image: UIImage) -> UIImage? {
        guard let cg = image.cgImage else { return image }
        let scale = min(1, 256 / CGFloat(max(cg.width, cg.height)))
        let w = max(1, Int(CGFloat(cg.width) * scale))
        let h = max(1, Int(CGFloat(cg.height) * scale))
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            context.interpolationQuality = .low
            context.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return image }
        let pixels = KotlinIntArray(size: Int32(w * h))
        for i in 0..<(w * h) {
            let o = i * 4
            let argb = (0xFF << 24) | (Int(bytes[o]) << 16) | (Int(bytes[o + 1]) << 8) | Int(bytes[o + 2])
            pixels.set(index: Int32(i), value: Int32(truncatingIfNeeded: argb))
        }
        guard let box = BorderCrop.shared.find(pixels: pixels, width: Int32(w), height: Int32(h)) else { return image }
        // The small copy's rows run top to bottom, like the image's.
        let rect = CGRect(
            x: CGFloat(box.get(index: 0)) / scale, y: CGFloat(box.get(index: 1)) / scale,
            width: CGFloat(box.get(index: 2) - box.get(index: 0)) / scale,
            height: CGFloat(box.get(index: 3) - box.get(index: 1)) / scale
        ).integral.intersection(CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        guard let cropped = cg.cropping(to: rect) else { return image }
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    }
}
