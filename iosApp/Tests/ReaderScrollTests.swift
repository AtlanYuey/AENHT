import UIKit
import SwiftUI
import XCTest

@MainActor
final class ReaderScrollTests: XCTestCase {
    func testSwiftUIAnchorConnectsToTheActualScrollView() async throws {
        let driver = ReaderScrollDriver()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        let host = UIHostingController(rootView:
            ScrollView {
                Color.clear.frame(height: 2000)
                    .background(ReaderPageAnchor(index: 0, driver: driver))
            }.ignoresSafeArea()
        )
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { driver.disconnect(); window.isHidden = true }
        func find<T: UIView>(_ type: T.Type, in view: UIView) -> T? {
            if let match = view as? T { return match }
            return view.subviews.lazy.compactMap { find(type, in: $0) }.first
        }
        for _ in 0..<20 {
            host.view.layoutIfNeeded()
            if let anchor = find(ReaderPageAnchorView.self, in: host.view), anchor.bounds.height > 1000 { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        let scroll = try XCTUnwrap(find(UIScrollView.self, in: host.view))
        let anchor = try XCTUnwrap(find(ReaderPageAnchorView.self, in: host.view))
        XCTAssertEqual(anchor.bounds.height, 2000, accuracy: 1)
        driver.image(index: 0, size: anchor.bounds.size)
        driver.lastPage = 0
        driver.secondsPerScreen = 1
        let previous = scroll.contentOffset.y
        driver.start()
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThan(scroll.contentOffset.y, previous)
    }

    private final class ManualScrollView: UIScrollView {
        var simulatedTracking = false
        var simulatedDragging = false
        var simulatedDecelerating = false
        override var isTracking: Bool { simulatedTracking }
        override var isDragging: Bool { simulatedDragging }
        override var isDecelerating: Bool { simulatedDecelerating }
    }

    private func fixture() -> (ReaderScrollDriver, ManualScrollView, [UIView]) {
        let scroll = ManualScrollView(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.contentSize = CGSize(width: 400, height: 6000)
        let driver = ReaderScrollDriver()
        driver.secondsPerScreen = 1
        driver.lastPage = 2
        let rows = (0..<3).map { index -> UIView in
            let view = UIView(frame: CGRect(x: 0, y: CGFloat(index * 2000), width: 400, height: 2000))
            scroll.addSubview(view)
            driver.register(index: index, view: view)
            driver.image(index: index, size: CGSize(width: 400, height: 2000))
            return view
        }
        return (driver, scroll, rows)
    }

    func testFastMotionHasTheSameSpeedAt60And120Hz() {
        for hz in [60, 120] {
            var total: CGFloat = 0
            for _ in 0..<hz { total += ReaderScrollMotion.distance(viewport: 800, secondsPerScreen: 1, elapsed: 1.0 / Double(hz)) }
            XCTAssertEqual(total, 800, accuracy: 0.5)
        }
    }

    func testSlowMotionRetainsSubpixelsAt120Hz() {
        var total: CGFloat = 0
        for _ in 0..<3600 { total += ReaderScrollMotion.distance(viewport: 800, secondsPerScreen: 30, elapsed: 1.0 / 120) }
        XCTAssertEqual(total, 800, accuracy: 1.0 / 3)
    }

    func testAStalledFrameDoesNotSkipSecondsOfUnreadContent() {
        XCTAssertEqual(ReaderScrollMotion.distance(viewport: 800, secondsPerScreen: 1, elapsed: 5), 40)
        XCTAssertEqual(ReaderScrollMotion.distance(viewport: 800, secondsPerScreen: 1, elapsed: -1), 0)
        XCTAssertEqual(ReaderScrollMotion.distance(viewport: 800, secondsPerScreen: .nan, elapsed: 0.016), 0)
    }

    func testThreeSecondsUsesEvenNativeStepsInsteadOfAlternatingPixelSteps() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.secondsPerScreen = 3
        driver.start()
        let expected: CGFloat = 800 / 3 / 60
        for _ in 0..<60 {
            let before = scroll.contentOffset.y
            driver.advance(elapsed: 1.0 / 60, scale: 2)
            XCTAssertEqual(scroll.contentOffset.y - before, expected, accuracy: 0.000001)
        }
    }

    func testPageProgressFollowsAutomaticMotionManualDragAndStoppedScrolling() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        var reported: Int?
        driver.onPage = { reported = $0 }
        driver.continueOnDrag = true
        driver.start()
        scroll.setContentOffset(CGPoint(x: 0, y: 1999), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertEqual(reported, 1)
        scroll.simulatedDragging = true
        scroll.setContentOffset(CGPoint(x: 0, y: 4200), animated: false)
        driver.viewportChanged(CGSize(width: 400, height: 800))
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertEqual(reported, 2)
        XCTAssertTrue(driver.running)
        driver.stop()
        scroll.simulatedDragging = false
        scroll.setContentOffset(CGPoint(x: 0, y: 0), animated: false)
        driver.viewportChanged(CGSize(width: 400, height: 800))
        XCTAssertEqual(reported, 0)
    }

    func testActualUIKitPositionWinsOverThePreviousAutomaticPosition() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.start()
        for _ in 0..<20 { driver.advance(elapsed: 1.0 / 60, scale: 2) }
        // Lazy image layout / system anchoring corrected the offset since the last display frame.
        scroll.setContentOffset(CGPoint(x: 0, y: 1800), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThan(scroll.contentOffset.y, 1800)
        XCTAssertLessThan(scroll.contentOffset.y, 1815)
    }

    func testTemporaryContentSizeEstimateNeverForcesABackwardJump() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.start()
        scroll.contentSize.height = 1200
        scroll.setContentOffset(CGPoint(x: 0, y: 900), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThanOrEqual(scroll.contentOffset.y, 900)
        XCTAssertTrue(driver.running)
    }

    func testManualDraggingStillStopsByDefault() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        XCTAssertFalse(driver.continueOnDrag)
        driver.start()
        scroll.simulatedDragging = true
        driver.userInteractionStarted()
        XCTAssertFalse(driver.running)
    }

    func testContinueOptionYieldsToFingerAndInertiaThenResumesAtNewPosition() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.continueOnDrag = true
        driver.start()
        scroll.simulatedTracking = true
        scroll.simulatedDragging = true
        driver.userInteractionStarted()
        scroll.setContentOffset(CGPoint(x: 0, y: 700), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertTrue(driver.running)
        XCTAssertEqual(scroll.contentOffset.y, 700)
        scroll.simulatedDragging = false
        scroll.simulatedTracking = false
        scroll.simulatedDecelerating = true
        scroll.setContentOffset(CGPoint(x: 0, y: 1100), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertEqual(scroll.contentOffset.y, 1100)
        scroll.simulatedDecelerating = false
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThan(scroll.contentOffset.y, 1100)
        XCTAssertLessThan(scroll.contentOffset.y, 1115)
    }

    func testAControlsTapDoesNotStopPlaybackBeforeItBecomesADrag() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.start()
        scroll.simulatedTracking = true
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertTrue(driver.running)
        XCTAssertEqual(scroll.contentOffset.y, 0)
    }

    func testLoadingBoundaryWaitsAndResumesWithoutCatchingUp() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        driver.image(index: 1)
        scroll.setContentOffset(CGPoint(x: 0, y: 1190), animated: false)
        driver.start()
        for _ in 0..<60 { driver.advance(elapsed: 1.0 / 60, scale: 2) }
        XCTAssertLessThanOrEqual(scroll.contentOffset.y, 1200)
        driver.image(index: 1, size: CGSize(width: 400, height: 2000))
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThan(scroll.contentOffset.y, 1200)
        XCTAssertLessThan(scroll.contentOffset.y, 1215)
    }

    func testFailureStopsAndReportsTheImageNeedingRetry() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        var failure: Int?
        driver.onStop = { if case .failed(let index) = $0 { failure = index } }
        driver.image(index: 1, failed: true)
        scroll.setContentOffset(CGPoint(x: 0, y: 1200), animated: false)
        driver.start()
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertFalse(driver.running)
        XCTAssertEqual(failure, 1)
    }

    func testLastLongImageDoesNotStopAtItsPageNumber() {
        let (driver, scroll, _) = fixture()
        defer { driver.stop() }
        scroll.setContentOffset(CGPoint(x: 0, y: 4800), animated: false)
        driver.start()
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertTrue(driver.running)
        XCTAssertGreaterThan(scroll.contentOffset.y, 4800)
        scroll.setContentOffset(CGPoint(x: 0, y: 5200), animated: false)
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertFalse(driver.running)
    }

    func testRealImageHeightMustSettleBeforeFinishing() {
        let (driver, scroll, rows) = fixture()
        defer { driver.stop() }
        rows[2].frame.size.height = 500
        driver.image(index: 2, size: CGSize(width: 400, height: 4000))
        scroll.setContentOffset(CGPoint(x: 0, y: 4000), animated: false)
        driver.start()
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertTrue(driver.running)
        rows[2].frame.size.height = 4000
        scroll.contentSize.height = 8000
        driver.advance(elapsed: 1.0 / 60, scale: 2)
        XCTAssertGreaterThan(scroll.contentOffset.y, 4000)
    }

    func testToolbarHeightChangesDoNotPausePlaybackButRotationDoes() {
        let (driver, _, _) = fixture()
        defer { driver.stop() }
        driver.viewportChanged(CGSize(width: 400, height: 800))
        driver.start()
        driver.viewportChanged(CGSize(width: 400, height: 700))
        XCTAssertTrue(driver.running)
        driver.viewportChanged(CGSize(width: 420, height: 700))
        XCTAssertFalse(driver.running)
    }

    func testDecodedRatioSurvivesLazyRowRecycling() {
        let (driver, _, rows) = fixture()
        driver.unregister(index: 1, view: rows[1])
        XCTAssertEqual(driver.knownRatios[1], 0.2)
    }
}
