import XCTest

/// Which Apple Pencil gestures an iPad's pencils have, from its model.
final class ApplePencilSupportTests: XCTestCase {
    func testSecondGenerationIPadsDoubleTapOnly() {
        for model in ["iPad8,1", "iPad13,1", "iPad14,1", "iPad14,6"] {
            XCTAssertEqual(PencilSupport.of(model: model), .doubleTap, model)
        }
        XCTAssertTrue(PencilSupport.doubleTap.doubleTaps)
        XCTAssertFalse(PencilSupport.doubleTap.squeezes)
    }

    func testProIPadsSqueeze() {
        for model in ["iPad14,8", "iPad15,3", "iPad16,1", "iPad16,5"] {
            XCTAssertEqual(PencilSupport.of(model: model), .squeeze, model)
        }
        XCTAssertTrue(PencilSupport.squeeze.doubleTaps)
        XCTAssertTrue(PencilSupport.squeeze.squeezes)
    }

    func testFirstGenerationAndUSBCIPadsHaveNoGestures() {
        // iPad (9th generation, 2021), iPad (10th), iPad (A16), iPad Air (3rd).
        for model in ["iPad12,1", "iPad13,18", "iPad15,7", "iPad11,3"] {
            XCTAssertEqual(PencilSupport.of(model: model), .none, model)
        }
        XCTAssertFalse(PencilSupport.none.doubleTaps)
    }

    func testUnknownNewerIPadsOfferBoth() {
        XCTAssertEqual(PencilSupport.of(model: "iPad18,1"), .unknown)
        XCTAssertEqual(PencilSupport.of(model: "something"), .unknown)
        XCTAssertTrue(PencilSupport.unknown.doubleTaps)
        XCTAssertTrue(PencilSupport.unknown.squeezes)
    }

    func testSeenGesturesOnlyGoUp() {
        XCTAssertLessThan(PencilSeen.nothing, PencilSeen.doubleTap)
        XCTAssertLessThan(PencilSeen.doubleTap, PencilSeen.squeeze)
    }
}
