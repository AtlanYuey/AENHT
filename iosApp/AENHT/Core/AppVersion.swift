import Foundation

enum AppVersion {
    static var current: String { version(in: Bundle.main.infoDictionary ?? [:]) }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-" }
    static var display: String { "\(current) (\(build))" }

    static func version(in info: [String: Any]) -> String {
        let numeric = info["CFBundleShortVersionString"] as? String ?? "0"
        let suffix = (info["AENHTPrerelease"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return suffix.isEmpty ? numeric : "\(numeric)-\(suffix)"
    }
}
