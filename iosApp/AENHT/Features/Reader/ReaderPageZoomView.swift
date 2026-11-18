import UIKit

/// The native scroll canvas for a single page or a joined spread.
@MainActor
class ReaderPageZoomView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    var onSizeChanged: ((ReaderSizeStatus) -> Void)?
    private var artworkScale = ReaderPageSizing.normalScale
    private var reportedSize = ReaderSizeStatus()
    private var pinchStart: (scale: CGFloat, focus: CGPoint)?
    private let pinchDelegate = PinchDelegate()
    #if DEBUG
    var testPage: Int?
    #endif

    var sizeStatus: ReaderSizeStatus {
        ReaderSizeStatus(scale: artworkScale, adjusting: pinchStart != nil)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .normal
        bouncesHorizontally = false
        panGestureRecognizer.maximumNumberOfTouches = 1
        backgroundColor = .black

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        addSubview(imageView)

        // Pinch around the artwork under the fingers. The scroll view owns
        // the single-finger drag and deceleration at the resulting size.
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        pinchDelegate.scroll = self
        pinch.delegate = pinchDelegate
        addGestureRecognizer(pinch)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func display(_ image: UIImage?) {
        imageView.image = image
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateCanvas()
    }

    private func updateCanvas() {
        var size = CGSize.zero
        if let image = imageView.image, image.size.width > 0, image.size.height > 0 {
            let fit = min(bounds.width / image.size.width, bounds.height / image.size.height) * artworkScale
            size = CGSize(width: image.size.width * fit, height: image.size.height * fit)
        }
        let frame = CGRect(x: max(0, (bounds.width - size.width) / 2),
                           y: max(0, (bounds.height - size.height) / 2),
                           width: size.width, height: size.height)
        if imageView.frame != frame { imageView.frame = frame }
        if contentSize != size { contentSize = size }
        recordViewport()
    }

    private func recordViewport() {
        #if DEBUG
        if imageView.image != nil { ReaderGestureTestSupport.measure(self, page: testPage, scale: artworkScale) }
        #endif
    }

    func resetZoom() {
        pinchStart = nil
        artworkScale = ReaderPageSizing.normalScale
        updateCanvas()
        setContentOffset(.zero, animated: false)
        reportSize()
    }

    func setArtworkScale(_ scale: CGFloat, centeredAt point: CGPoint? = nil, animated: Bool = false) {
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let focus = imageFraction(at: point ?? convert(center, to: imageView))
        let destination = CGPoint(x: bounds.width / 2, y: bounds.height / 2)
        let resize = { self.resize(to: scale, focus: focus, under: destination) }
        if animated {
            UIView.animate(withDuration: 0.25, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: resize)
        } else {
            resize()
        }
    }

    private func imageFraction(at point: CGPoint) -> CGPoint {
        CGPoint(x: point.x / max(1, imageView.bounds.width), y: point.y / max(1, imageView.bounds.height))
    }

    private func resize(to scale: CGFloat, focus: CGPoint, under viewportPoint: CGPoint) {
        artworkScale = ReaderPageSizing.clamp(scale)
        updateCanvas()
        let frame = imageView.frame
        let x = frame.minX + frame.width * focus.x - viewportPoint.x
        let y = frame.minY + frame.height * focus.y - viewportPoint.y
        setContentOffset(CGPoint(x: min(max(0, x), max(0, contentSize.width - bounds.width)),
                                 y: min(max(0, y), max(0, contentSize.height - bounds.height))), animated: false)
        reportSize()
    }

    #if DEBUG
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        recordViewport()
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        ReaderGestureTestSupport.recordDrag(of: self)
    }
    #endif

    func sizeDidChange(_ status: ReaderSizeStatus) {}

    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began:
            guard imageView.image != nil else { return }
            pinchStart = (artworkScale, imageFraction(at: gesture.location(in: imageView)))
            reportSize()
        case .changed:
            guard let start = pinchStart else { return }
            let touch = gesture.location(in: self)
            let visiblePoint = CGPoint(x: touch.x - bounds.minX, y: touch.y - bounds.minY)
            resize(to: start.scale * gesture.scale, focus: start.focus, under: visiblePoint)
        case .ended, .cancelled, .failed:
            pinchStart = nil
            reportSize()
        default:
            break
        }
    }

    private final class PinchDelegate: NSObject, UIGestureRecognizerDelegate {
        weak var scroll: ReaderPageZoomView?

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            scroll?.imageView.image != nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            other === scroll?.panGestureRecognizer
        }
    }

    private func reportSize() {
        let status = sizeStatus
        guard status != reportedSize else { return }
        reportedSize = status
        sizeDidChange(status)
        onSizeChanged?(status)
    }
}
