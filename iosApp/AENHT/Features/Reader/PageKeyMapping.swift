import Foundation

/// The keys of a keyboard or a Bluetooth page turner that turn pages.
enum PageKey: Equatable {
    /// Down, Page Down, Space: the next page whatever the direction.
    case next
    /// Up, Page Up, Shift-Space: the previous page.
    case previous
    /// The arrows left and right: the page on that side (by the reading direction).
    case right, left

    /// Whether [key] turns forward, reading [rightToLeft]; [swap] (Swap Next
    /// and Previous) for page turners whose buttons are the other way round.
    static func forward(_ key: PageKey, rightToLeft: Bool, swap: Bool) -> Bool {
        let forward: Bool
        switch key {
        case .next: forward = true
        case .previous: forward = false
        case .right: forward = !rightToLeft
        case .left: forward = rightToLeft
        }
        return swap ? !forward : forward
    }
}
