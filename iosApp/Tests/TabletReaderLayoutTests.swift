import Foundation
import XCTest

final class TabletReaderLayoutTests: XCTestCase {
    func testRegularPageFitsLandscapeAtDefaultSize() {
        let viewport = CGSize(width: 1194, height: 834)
        let ratio: CGFloat = 0.7
        let width = ReaderPageSizing.width(in: viewport, aspectRatio: ratio, scale: 1)
        XCTAssertEqual(width / ratio, viewport.height, accuracy: 0.001)
        XCTAssertLessThan(width, viewport.width)
    }

    func testTallStripKeepsReadableWidthWhenPinchedIn() {
        let viewport = CGSize(width: 1194, height: 834)
        let width = ReaderPageSizing.width(in: viewport, aspectRatio: 0.1, scale: 0)
        XCTAssertEqual(width, viewport.width * 0.42 * ReaderPageSizing.minimumScale, accuracy: 0.001)
        XCTAssertGreaterThan(width, viewport.height * 0.1)
    }

    func testOnlyEnlargingOrAnActivePinchBlocksReaderNavigation() {
        XCTAssertFalse(ReaderSizeStatus().adjusted)
        XCTAssertTrue(ReaderSizeStatus(scale: 0.8).adjusted)
        XCTAssertFalse(ReaderSizeStatus(scale: 0.8).blocksNavigation)
        XCTAssertTrue(ReaderSizeStatus(scale: 1.5).blocksNavigation)
        XCTAssertTrue(ReaderSizeStatus(scale: 0.8, adjusting: true).blocksNavigation)
        XCTAssertEqual(ReaderPageSizing.clamp(.infinity), 1)
    }

    func testCompactPaneFitsThreeFullSizeThumbnails() throws {
        let viewport = CGSize(width: 1194, height: 834)
        let normal = WideGalleryPage(viewport: viewport, compactPages: false)
        let compact = WideGalleryPage(viewport: viewport, compactPages: true)
        let tile = try XCTUnwrap(compact.compactThumbnailWidth)
        XCTAssertEqual(normal.rightWidth, 597)
        XCTAssertEqual(tile, (597 - 32 - 40) / 5, accuracy: 0.001)
        XCTAssertEqual(compact.rightWidth, 3 * tile + 20 + 32, accuracy: 0.001)
        XCTAssertLessThan(compact.rightWidth, normal.rightWidth)
        XCTAssertFalse(WideGalleryPage.fits(CGSize(width: 834, height: 1194)))
        XCTAssertFalse(WideGalleryPage.fits(CGSize(width: 780, height: 600)))
    }
}
