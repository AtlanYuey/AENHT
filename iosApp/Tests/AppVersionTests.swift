import XCTest

final class AppVersionTests: XCTestCase {
    func testBetaSuffixIsIncludedForDisplayAndUpdateChecks() {
        XCTAssertEqual(AppVersion.version(in: ["CFBundleShortVersionString": "0.2.2", "AENHTPrerelease": "beta.1"]), "0.2.2-beta.1")
    }

    func testStableBuildWithoutASuffixKeepsItsNumericVersion() {
        XCTAssertEqual(AppVersion.version(in: ["CFBundleShortVersionString": "0.2.2"]), "0.2.2")
        XCTAssertEqual(AppVersion.version(in: ["CFBundleShortVersionString": "0.2.2", "AENHTPrerelease": "  "]), "0.2.2")
    }

    func testMissingInfoDoesNotProduceAnUnresolvedBuildSetting() {
        XCTAssertEqual(AppVersion.version(in: [:]), "0")
    }
}
