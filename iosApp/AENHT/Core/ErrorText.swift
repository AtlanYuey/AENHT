import Foundation
import Shared

/// What an error says to the user: a failed connection in one short
/// sentence ([NetFailure]), never the system's whole error dump (that goes
/// to the diagnostics log).
enum ErrorText {
    /// An English sentence from the shared code, in the app's language.
    static func localized(_ message: String) -> String {
        String(localized: String.LocalizationValue(message))
    }

    static func of(_ error: Error) -> String {
        let nsError = error as NSError
        if let kotlin = nsError.userInfo["KotlinException"] as? KotlinThrowable {
            // Already one of the short sentences (the shared code's own failures).
            if let message = kotlin.message, NetFailure.shared.all.contains(message) { return localized(message) }
            if let short = NetFailure.shared.describeOrNull(e: kotlin) { return localized(short) }
            return kotlin.message ?? error.localizedDescription
        }
        if nsError.domain == NSURLErrorDomain { return shortURLError(nsError.code) }
        return error.localizedDescription
    }

    private static func shortURLError(_ code: Int) -> String {
        switch code {
        case NSURLErrorNotConnectedToInternet, NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed:
            localized(NetFailure.shared.OFFLINE)
        case NSURLErrorTimedOut: localized(NetFailure.shared.TIMEOUT)
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateHasUnknownRoot, NSURLErrorServerCertificateNotYetValid:
            localized(NetFailure.shared.SECURE)
        case NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost,
             NSURLErrorDNSLookupFailed:
            localized(NetFailure.shared.UNREACHABLE)
        default: localized(NetFailure.shared.OTHER)
        }
    }
}
