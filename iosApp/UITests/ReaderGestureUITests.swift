import XCTest

/// The reader's one-finger moves on an enlarged page, on an iPad simulator,
/// with the real reader on local pictures (ReaderFixture).
@MainActor
final class ReaderGestureUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    private func open(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--reader-fixture"] + arguments
        app.launch()
        let thumb = app.buttons["fixture-page-3"]
        XCTAssertTrue(thumb.waitForExistence(timeout: 20))
        thumb.tap()
        XCTAssertTrue(app.staticTexts["reader-metrics"].waitForExistence(timeout: 10))
        sleep(2)
        return app
    }

    private func metrics(_ app: XCUIApplication) -> [String: Double] {
        let label = app.staticTexts["reader-metrics"].label
        var values: [String: Double] = [:]
        for part in label.split(separator: " ") {
            let pair = part.split(separator: "=")
            if pair.count == 2, let value = Double(pair[1]) { values[String(pair[0])] = value }
        }
        return values
    }

    private func drag(_ app: XCUIApplication, from: CGVector, to: CGVector) {
        let window = app.windows.firstMatch
        let start = window.coordinate(withNormalizedOffset: from)
        let end = window.coordinate(withNormalizedOffset: to)
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.3)
        sleep(1)
    }

    /// Pinches the page bigger, then drags one finger sideways, down and diagonally.
    private func checkEnlargedMoves(_ arguments: [String], name: String) {
        let app = open(arguments)
        print("[\(name)] opened: \(app.staticTexts["reader-metrics"].label)")
        app.windows.firstMatch.pinch(withScale: 2.6, velocity: 1.2)
        sleep(2)
        let zoomed = metrics(app)
        print("[\(name)] pinched: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertGreaterThan(max(zoomed["scale"] ?? 0, (zoomed["maxX"] ?? 0) > 1 ? 2 : 0), 1.05, "\(name): the page wasn't enlarged")

        let goLeft = (zoomed["x"] ?? 0) > (zoomed["maxX"] ?? 0) / 2
        drag(app, from: CGVector(dx: goLeft ? 0.35 : 0.65, dy: 0.5), to: CGVector(dx: goLeft ? 0.65 : 0.35, dy: 0.5))
        let sideways = metrics(app)
        print("[\(name)] after a sideways drag: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertGreaterThan(abs((sideways["x"] ?? 0) - (zoomed["x"] ?? 0)), 40, "\(name): a sideways drag didn't move the page")

        let goUp = (sideways["y"] ?? 0) > (sideways["maxY"] ?? 0) / 2
        drag(app, from: CGVector(dx: 0.5, dy: goUp ? 0.35 : 0.65), to: CGVector(dx: 0.5, dy: goUp ? 0.65 : 0.35))
        let down = metrics(app)
        print("[\(name)] after a vertical drag: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertGreaterThan(abs((down["y"] ?? 0) - (sideways["y"] ?? 0)), 40, "\(name): a vertical drag didn't move the page")

        drag(app, from: CGVector(dx: 0.4, dy: 0.4), to: CGVector(dx: 0.6, dy: 0.6))
        print("[\(name)] after a diagonal drag: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertTrue(app.staticTexts["reader-metrics"].exists, "\(name): the reader closed")
    }

    // MARK: The top: a single tap on the status bar does nothing, a double tap goes to the first page.

    private func tapStatusBar(_ app: XCUIApplication, twice: Bool) {
        let bar = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0)).withOffset(CGVector(dx: 0, dy: 6))
        if twice { bar.doubleTap() } else { bar.tap() }
        sleep(2)
    }

    private func pageLabel(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.staticTexts[text]
    }

    func testStatusBarSingleTapStays() {
        let app = open(["--fixture-strip"])
        XCTAssertTrue(pageLabel(app, "3 / 12").waitForExistence(timeout: 5), "opened on page 3: \(app.staticTexts["reader-metrics"].label)")
        tapStatusBar(app, twice: false)
        print("[top] after a single tap: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertTrue(pageLabel(app, "3 / 12").exists, "a single tap on the status bar moved the strip")
        tapStatusBar(app, twice: true)
        print("[top] after a double tap: \(app.staticTexts["reader-metrics"].label)")
        XCTAssertTrue(pageLabel(app, "1 / 12").waitForExistence(timeout: 5), "a double tap on the status bar didn't go to the first page")
    }

    func testTitleDoubleTapPaged() {
        let app = open(["--fixture-single"])
        XCTAssertTrue(pageLabel(app, "3 / 12").waitForExistence(timeout: 5))
        tapStatusBar(app, twice: false)
        XCTAssertTrue(pageLabel(app, "3 / 12").exists, "a single tap on the status bar turned the page")
        pageLabel(app, "3 / 12").doubleTap()
        XCTAssertTrue(pageLabel(app, "1 / 12").waitForExistence(timeout: 5), "a double tap on the title didn't go to the first page")
    }

    func testTopTapsOff() {
        let app = open(["--fixture-strip", "--fixture-no-top-tap"])
        XCTAssertTrue(pageLabel(app, "3 / 12").waitForExistence(timeout: 5))
        tapStatusBar(app, twice: true)
        XCTAssertTrue(pageLabel(app, "3 / 12").exists, "turned off, a double tap on the status bar still moved")
        XCTAssertTrue(app.staticTexts["reader-metrics"].exists, "the reader closed")
    }

    func testDoublePageSlide() { checkEnlargedMoves([], name: "double page, slide") }
    func testDoublePageSweep() { checkEnlargedMoves(["--fixture-sweep"], name: "double page, swipe fade") }
    func testSinglePageSlide() { checkEnlargedMoves(["--fixture-single"], name: "single page, slide") }
    func testStrip() { checkEnlargedMoves(["--fixture-strip"], name: "continuous") }
}
