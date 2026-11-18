import XCTest

@MainActor
final class ReaderGestureUITests: XCTestCase {
    private struct Canvas {
        let x: Double
        let y: Double
        let maxX: Double
        let maxY: Double
        let scale: Double
        let page: Int
        let overshoot: Double
    }

    func testPinchedDoublePageMovesWithOneFinger() throws {
        let app = openReader()
        let canvas = canvas(in: app)
        XCTAssertEqual(try XCTUnwrap(metrics(canvas)).page, 0)
        drag(app, from: (0.78, 0.45), to: (0.24, 0.45))
        eventually { self.metrics(canvas)?.page == 1 }
        canvas.pinch(withScale: 2.8, velocity: 1.7)
        eventually { (self.metrics(canvas)?.scale ?? 0) > 1.4 }
        let before = try XCTUnwrap(metrics(canvas))
        XCTAssertGreaterThan(before.maxX, 100)
        XCTAssertGreaterThan(before.maxY, 100)

        let endX = before.x < before.maxX * 0.55 ? 0.30 : 0.70
        let endY = before.y < before.maxY * 0.55 ? 0.30 : 0.70
        drag(app, from: (1 - endX, 1 - endY), to: (endX, endY))
        print("Spread offset before drag", before.x, before.y, "and after drag", canvas.value ?? "no viewport measurement")
        eventually {
            guard let after = self.metrics(canvas) else { return false }
            return abs(after.x - before.x) > 50 && abs(after.y - before.y) > 35
        }
        XCTAssertEqual(try XCTUnwrap(metrics(canvas)).page, before.page)
        XCTAssertFalse(app.staticTexts["reader-gallery"].exists)
    }

    func testStripPansTheFullArtworkAndHasHardSideEdges() throws {
        let app = openReader("--reader-strip")
        let canvas = canvas(in: app)
        XCTAssertLessThan(try XCTUnwrap(metrics(canvas)).maxX, 1)
        drag(app, from: (0.77, 0.45), to: (0.23, 0.45))
        XCTAssertLessThan(abs(try XCTUnwrap(metrics(canvas)).x), 1)

        canvas.pinch(withScale: 3.3, velocity: 1.5)
        eventually { (self.metrics(canvas)?.maxX ?? 0) > 250 }
        let before = try XCTUnwrap(metrics(canvas))
        let canMoveRight = before.x < before.maxX / 2
        drag(app, from: (canMoveRight ? 0.76 : 0.24, 0.45), to: (canMoveRight ? 0.24 : 0.76, 0.45))
        eventually { abs((self.metrics(canvas)?.x ?? before.x) - before.x) > 130 }

        for _ in 0..<6 {
            drag(app, from: (0.82, 0.45), to: (0.18, 0.45))
            if let m = metrics(canvas), abs(m.x - m.maxX) < 2 { break }
        }
        let end = try XCTUnwrap(metrics(canvas))
        XCTAssertEqual(end.x, end.maxX, accuracy: 2)
        drag(app, from: (0.82, 0.45), to: (0.18, 0.45), hold: 0.35)
        XCTAssertLessThan(try XCTUnwrap(metrics(canvas)).overshoot, 1)

        for _ in 0..<6 {
            drag(app, from: (0.18, 0.45), to: (0.82, 0.45))
            if let m = metrics(canvas), abs(m.x) < 2 { break }
        }
        XCTAssertEqual(try XCTUnwrap(metrics(canvas)).x, 0, accuracy: 2)
    }

    func testEdgeReturnWaitsForReleaseAndIgnoresShortSwipes() throws {
        let app = openReader()
        let canvas = canvas(in: app)
        canvas.pinch(withScale: 2.1, velocity: 1.4)
        print("Before testing the edge", canvas.value ?? "no viewport measurement")
        eventually { (self.metrics(canvas)?.scale ?? 0) > 1.4 }
        drag(app, from: (0.003, 0.50), to: (0.043, 0.50), hold: 0.4)
        XCTAssertTrue(canvas.exists)
        XCTAssertFalse(app.staticTexts["reader-gallery"].exists)

        drag(app, from: (0.003, 0.50), to: (0.45, 0.50), hold: 0.55)
        XCTAssertTrue(app.staticTexts["reader-gallery"].waitForExistence(timeout: 8))
        let events = app.staticTexts["reader-edge-events"].label.components(separatedBy: ",")
        XCTAssertTrue(events.contains("short"), events.description)
        let released = try XCTUnwrap(events.firstIndex(of: "released"), events.description)
        let departure = try XCTUnwrap(events.firstIndex(of: "departure"), events.description)
        XCTAssertLessThan(released, departure)
    }

    func testStripReturnsToTheThumbnailOfALaterPage() throws {
        try assertStripReturn("--reader-strip")
    }

    func testGappedStripReturnsToTheThumbnailOfALaterPage() throws {
        try assertStripReturn("--reader-gaps")
    }

    private func assertStripReturn(_ mode: String) throws {
        let app = openReader(mode)
        let canvas = canvas(in: app)
        let options = app.buttons["reader-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 5))
        options.tap()
        let jump = app.buttons["reader-go-to-page"].firstMatch
        XCTAssertTrue(jump.waitForExistence(timeout: 5))
        jump.tap()
        let input = app.alerts.textFields.firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        input.typeText(XCUIKeyboardKey.delete.rawValue + "23")
        app.alerts.buttons["Go"].tap()
        eventually {
            guard let reading = self.metrics(canvas) else { return false }
            return reading.page >= 21
        }
        let displayed = try XCTUnwrap(metrics(canvas)).page + 1
        let back = app.buttons["reader-back"]
        if !back.exists { canvas.tap() }
        XCTAssertTrue(back.waitForExistence(timeout: 6))
        back.tap()
        XCTAssertTrue(app.staticTexts["reader-gallery"].waitForExistence(timeout: 8))
        eventually { app.staticTexts["reader-return-target"].label == "Return target \(displayed)" }
        XCTAssertEqual(app.staticTexts["reader-gallery-position"].label, "Current thumbnail \(displayed)")
        let thumbnail = app.descendants(matching: .any)["reader-thumbnail-\(displayed)"].firstMatch
        XCTAssertTrue(thumbnail.exists && thumbnail.isHittable)
    }

    private func openReader(_ mode: String? = nil, file: StaticString = #filePath, line: UInt = #line) -> XCUIApplication {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication()
        app.launchArguments = ["--reader-gesture-test", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if let mode { app.launchArguments.append(mode) }
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        eventually(file: file, line: line) { app.frame.width > app.frame.height }
        let first = app.descendants(matching: .any)["reader-thumbnail-1"].firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 15), file: file, line: line)
        first.tap()
        let view = canvas(in: app)
        XCTAssertTrue(view.waitForExistence(timeout: 12), file: file, line: line)
        eventually { (self.metrics(view)?.maxY ?? 0) > 0 || self.metrics(view) != nil }
        return app
    }

    private func canvas(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["reader-canvas"].firstMatch
    }

    private func drag(_ app: XCUIApplication, from: (Double, Double), to: (Double, Double), hold: TimeInterval = 0) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: from.0, dy: from.1))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: to.0, dy: to.1))
        start.press(forDuration: 0.07, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: hold)
    }

    private func metrics(_ element: XCUIElement) -> Canvas? {
        guard element.exists, let value = element.value as? String else { return nil }
        let values = value.split(separator: ",").compactMap { Double($0) }
        guard values.count == 7 else { return nil }
        return Canvas(x: values[0], y: values[1], maxX: values[2], maxY: values[3],
                      scale: values[4], page: Int(values[5]), overshoot: values[6])
    }

    private func eventually(file: StaticString = #filePath, line: UInt = #line,
                            _ condition: () -> Bool) {
        let until = Date().addingTimeInterval(8)
        repeat {
            if condition() { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.15))
        } while Date() < until
        XCTFail("The expected reader state did not appear", file: file, line: line)
    }
}
