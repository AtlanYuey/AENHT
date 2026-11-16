import Foundation

/// A missing item is different from a locked, unreadable or malformed one.
enum SecretRead {
    case value(String)
    case missing
    case unavailable
}

/// One Keychain value per site's complete credential tuple. A failed single-item
/// update keeps the previous tuple; it never requires a multi-item rollback.
final class AtomicCredentials: @unchecked Sendable {
    private let record: String
    private let legacyKeys: [String]
    private let read: (String) -> SecretRead
    private let write: (String, String) -> Bool
    private let remove: (String) -> Bool
    private let lock = NSLock()

    init(record: String, legacyKeys: [String], read: @escaping (String) -> SecretRead,
         write: @escaping (String, String) -> Bool, remove: @escaping (String) -> Bool) {
        self.record = record
        self.legacyKeys = legacyKeys
        self.read = read
        self.write = write
        self.remove = remove
    }

    func values() -> [String: String]? { lock.withLock { load() } }

    /// Nil/empty removes one field. Even clearing writes an authoritative empty
    /// record, so legacy items cannot reappear if their cleanup was refused.
    func update(_ changes: [(String, String?)]) -> Bool {
        lock.withLock {
            guard var values = load() else { return false }
            for (key, value) in changes {
                guard legacyKeys.contains(key) else { return false }
                if let value, !value.isEmpty { values[key] = value } else { values.removeValue(forKey: key) }
            }
            guard let bytes = try? JSONEncoder().encode(values),
                  let text = String(data: bytes, encoding: .utf8), write(record, text) else { return false }
            // The new record is authoritative even if deleting an old item fails.
            for key in legacyKeys { _ = remove(key) }
            return true
        }
    }

    private func load() -> [String: String]? {
        switch read(record) {
        case let .value(text):
            guard let bytes = text.data(using: .utf8),
                  let values = try? JSONDecoder().decode([String: String].self, from: bytes) else { return nil }
            return values.filter { !$0.value.isEmpty }
        case .unavailable:
            return nil // Never replace an unreadable tuple with a guessed empty one.
        case .missing:
            var values: [String: String] = [:]
            for key in legacyKeys {
                switch read(key) {
                case let .value(value): if !value.isEmpty { values[key] = value }
                case .missing: break
                case .unavailable: return nil
                }
            }
            return values
        }
    }
}
