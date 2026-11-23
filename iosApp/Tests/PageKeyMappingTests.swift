import XCTest

/// Which way a keyboard's or page turner's keys turn the pages.
final class PageKeyMappingTests: XCTestCase {
    func testNextAndPreviousIgnoreTheDirection() {
        for rtl in [false, true] {
            XCTAssertTrue(PageKey.forward(.next, rightToLeft: rtl, swap: false))
            XCTAssertFalse(PageKey.forward(.previous, rightToLeft: rtl, swap: false))
        }
    }

    func testArrowsFollowTheReadingDirection() {
        XCTAssertTrue(PageKey.forward(.right, rightToLeft: false, swap: false))
        XCTAssertFalse(PageKey.forward(.left, rightToLeft: false, swap: false))
        XCTAssertFalse(PageKey.forward(.right, rightToLeft: true, swap: false))
        XCTAssertTrue(PageKey.forward(.left, rightToLeft: true, swap: false))
    }

    func testSwapTurnsTheOtherWay() {
        XCTAssertFalse(PageKey.forward(.next, rightToLeft: false, swap: true))
        XCTAssertTrue(PageKey.forward(.previous, rightToLeft: false, swap: true))
        XCTAssertFalse(PageKey.forward(.right, rightToLeft: false, swap: true))
    }
}
