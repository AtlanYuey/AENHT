import Foundation
import UIKit

/// The gestures the pencil used with this iPad has sent, which tell which
/// pencil it is: a double-tap comes from Apple Pencil (2nd generation) or
/// Pro, a squeeze only from Apple Pencil Pro. Nothing tells the app the
/// pencil's model itself.
enum PencilSeen: Int, Comparable {
    case nothing = 0, doubleTap = 1, squeeze = 2

    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

/// The Apple Pencil gestures this iPad's pencils have, from its model
/// (the pencils each iPad works with are fixed).
enum PencilSupport: Equatable {
    /// Only Apple Pencil (1st generation) or (USB-C): no double-tap, no squeeze.
    case none
    /// Apple Pencil (2nd generation): a double-tap.
    case doubleTap
    /// Apple Pencil Pro: a double-tap and a squeeze.
    case squeeze
    /// A model this app doesn't know (newer): either may work.
    case unknown

    /// This device's ("iPad14,8"; the simulated one in the simulator); nil on an iPhone.
    @MainActor static var current: PencilSupport? {
        guard UIDevice.current.userInterfaceIdiom == .pad else { return nil }
        return of(model: modelIdentifier)
    }

    static var modelIdentifier: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// iPads that work with Apple Pencil (2nd generation).
    private static let secondGeneration: Set<String> = [
        "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4",      // iPad Pro 11-inch (1st)
        "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8",      // iPad Pro 12.9-inch (3rd)
        "iPad8,9", "iPad8,10",                           // iPad Pro 11-inch (2nd)
        "iPad8,11", "iPad8,12",                          // iPad Pro 12.9-inch (4th)
        "iPad13,1", "iPad13,2",                          // iPad Air (4th)
        "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7",  // iPad Pro 11-inch (3rd)
        "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11", // iPad Pro 12.9-inch (5th)
        "iPad13,16", "iPad13,17",                        // iPad Air (5th)
        "iPad14,1", "iPad14,2",                          // iPad mini (6th)
        "iPad14,3", "iPad14,4",                          // iPad Pro 11-inch (4th)
        "iPad14,5", "iPad14,6",                          // iPad Pro 12.9-inch (6th)
    ]

    /// iPads that work with Apple Pencil Pro.
    private static let pro: Set<String> = [
        "iPad14,8", "iPad14,9", "iPad14,10", "iPad14,11", // iPad Air 11/13-inch (M2)
        "iPad15,3", "iPad15,4", "iPad15,5", "iPad15,6",   // iPad Air 11/13-inch (M3)
        "iPad16,1", "iPad16,2",                           // iPad mini (A17 Pro)
        "iPad16,3", "iPad16,4", "iPad16,5", "iPad16,6",   // iPad Pro 11/13-inch (M4)
        "iPad17,1", "iPad17,2", "iPad17,3", "iPad17,4",   // iPad Pro 11/13-inch (M5)
    ]

    static func of(model: String) -> PencilSupport {
        if pro.contains(model) { return .squeeze }
        if secondGeneration.contains(model) { return .doubleTap }
        // "iPadN,M": every iPad up to the ones above is known (the others
        // only work with the 1st generation or USB-C pencil); a newer one isn't.
        let digits = model.dropFirst("iPad".count).prefix { $0.isNumber }
        guard model.hasPrefix("iPad"), let major = Int(digits) else { return .unknown }
        return major >= 17 ? .unknown : .none
    }

    var doubleTaps: Bool { self != .none }
    var squeezes: Bool { self == .squeeze || self == .unknown }
}
