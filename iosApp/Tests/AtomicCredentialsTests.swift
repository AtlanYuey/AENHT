import Foundation
import XCTest

final class AtomicCredentialsTests: XCTestCase {
    private final class Backend {
        var items = ["access": "A", "refresh": "A-refresh"]
        var unreadable = Set<String>()
        var failWrite = false
        var failCleanup = false
        var writes: [(String, String)] = []
        func store() -> AtomicCredentials {
            AtomicCredentials(record: "tuple", legacyKeys: ["access", "refresh"], read: { key in
                if self.unreadable.contains(key) { return .unavailable }
                return self.items[key].map(SecretRead.value) ?? .missing
            }, write: { key, text in
                self.writes.append((key, text))
                if self.failWrite { return false }
                self.items[key] = text
                return true
            }, remove: { key in
                if self.failCleanup { return false }
                self.items.removeValue(forKey: key)
                return true
            })
        }
    }

    func testLegacyReadHasNoWriteSideEffects() {
        let backend = Backend()
        XCTAssertEqual(backend.store().values(), ["access": "A", "refresh": "A-refresh"])
        XCTAssertTrue(backend.writes.isEmpty)
    }

    func testReplacementIsOneCompleteWrite() throws {
        let backend = Backend()
        let store = backend.store()
        XCTAssertTrue(store.update([("access", "C"), ("refresh", "C-refresh")]))
        XCTAssertEqual(backend.writes.count, 1)
        let encoded = try JSONDecoder().decode([String: String].self, from: Data(backend.writes[0].1.utf8))
        XCTAssertEqual(encoded, ["access": "C", "refresh": "C-refresh"])
        XCTAssertEqual(store.values(), encoded)
    }

    func testFailedReplacementPreservesEntireLegacyTuple() {
        let backend = Backend()
        backend.failWrite = true
        let store = backend.store()
        XCTAssertFalse(store.update([("access", "C"), ("refresh", "C-refresh")]))
        XCTAssertEqual(store.values(), ["access": "A", "refresh": "A-refresh"])
    }

    func testFailedUpdatePreservesExistingAtomicTuple() {
        let backend = Backend()
        let store = backend.store()
        XCTAssertTrue(store.update([("access", "B"), ("refresh", "B-refresh")]))
        backend.failWrite = true
        XCTAssertFalse(store.update([("access", "C"), ("refresh", "C-refresh")]))
        XCTAssertEqual(store.values(), ["access": "B", "refresh": "B-refresh"])
    }

    func testUnreadableLegacyItemCannotBeMigratedAsMissing() {
        let backend = Backend()
        backend.unreadable.insert("refresh")
        XCTAssertFalse(backend.store().update([("access", "C")]))
        XCTAssertTrue(backend.writes.isEmpty)
    }

    func testMalformedAtomicRecordNeverFallsBackToLegacySecrets() {
        let backend = Backend()
        backend.items["tuple"] = "broken record"
        let store = backend.store()
        XCTAssertNil(store.values())
        XCTAssertFalse(store.update([("access", "C")]))
        XCTAssertTrue(backend.writes.isEmpty)
    }

    func testClearRemainsAuthoritativeWhenLegacyCleanupFails() {
        let backend = Backend()
        backend.failCleanup = true
        let store = backend.store()
        XCTAssertTrue(store.update([("access", nil), ("refresh", nil)]))
        XCTAssertEqual(backend.items["access"], "A") // Simulated refused cleanup.
        XCTAssertEqual(backend.store().values(), [:]) // A new process must still see logout.
    }

    func testRefusedLogoutLeavesPreviousLoginIntact() {
        let backend = Backend()
        backend.failWrite = true
        let store = backend.store()
        XCTAssertFalse(store.update([("access", nil), ("refresh", nil)]))
        XCTAssertEqual(store.values(), ["access": "A", "refresh": "A-refresh"])
    }
}
