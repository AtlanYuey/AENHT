import UIKit

/// The native scroll canvas for a single page or a joined spread.
@MainActor
class ReaderPageZoomView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    var onSizeChanged: ((ReaderSizeStatus) -> Void)?
    private var adjustingSize = false
    private var reportedSize = ReaderSizeStatus()

    var sizeStatus: ReaderSizeStatus { ReaderSizeStatus(scale: zoomScale, adjusting: adjustingSize) }

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = ReaderPageSizing.minimumScale
        maximumZoomScale = ReaderPageSizing.maximumScale
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .normal
        bouncesZoom = true
        bouncesHorizontally = false
        backgroundColor = .black

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        addSubview(imageView)
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
        if let size = imageView.image?.size, size.width > 0, size.height > 0 {
            let fit = min(bounds.width / size.width, bounds.height / size.height)
            let fitted = CGSize(width: size.width * fit, height: size.height * fit)
            if fitted != imageView.bounds.size {
                imageView.bounds = CGRect(origin: .zero, size: fitted)
            }
        } else {
            imageView.bounds = .zero
        }
        updateCanvas()
    }

    private func updateCanvas() {
        // Use the artwork's fitted size. Black letterboxing is outside the canvas.
        let base = imageView.bounds.size
        let size = CGSize(width: base.width * zoomScale, height: base.height * zoomScale)
        if contentSize != size { contentSize = size }
        imageView.center = CGPoint(x: max(bounds.width, size.width) / 2,
                                   y: max(bounds.height, size.height) / 2)
        #if DEBUG
        if imageView.image != nil { ReaderGestureTestSupport.measure(self) }
        #endif
    }

    func resetZoom() {
        adjustingSize = false
        if zoomScale != ReaderPageSizing.normalScale {
            setZoomScale(ReaderPageSizing.normalScale, animated: false)
        }
        setContentOffset(.zero, animated: false)
        reportSize()
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        imageView.image == nil ? nil : imageView
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === panGestureRecognizer, !sizeStatus.enlarged { return false }
        return super.gestureRecognizerShouldBegin(gestureRecognizer)
    }

    #if DEBUG
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if imageView.image != nil { ReaderGestureTestSupport.measure(self) }
    }
    #endif

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        updateCanvas()
        reportSize()
    }

    func scrollViewWillBeginZooming(_ scrollView: UIScrollView, with view: UIView?) {
        adjustingSize = true
        reportSize()
    }

    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
        adjustingSize = false
        let clamped = ReaderPageSizing.clamp(scale)
        if clamped != zoomScale { setZoomScale(clamped, animated: false) }
        reportSize()
    }

    func sizeDidChange(_ status: ReaderSizeStatus) {}

    private func reportSize() {
        let status = sizeStatus
        guard status != reportedSize else { return }
        reportedSize = status
        sizeDidChange(status)
        onSizeChanged?(status)
    }
}
