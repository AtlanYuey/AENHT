package com.atlanyuey.aenht.shared

import com.atlanyuey.aenht.shared.net.NetFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetFailureTest {
    private val darwinOffline = "Exception in http request: Error Domain=NSURLErrorDomain Code=-1009 \"The Internet connection appears to be offline.\" " +
        "UserInfo={_kCFStreamErrorCodeKey=50, NSUnderlyingError=0x1 {Error Domain=kCFErrorDomainCFNetwork Code=-1009 \"(null)\"}}"

    @Test fun urlSessionCodes() {
        assertEquals(NetFailure.OFFLINE, NetFailure.describe(Exception(darwinOffline)))
        assertEquals(NetFailure.TIMEOUT, NetFailure.describe(Exception("Error Domain=NSURLErrorDomain Code=-1001 \"The request timed out.\"")))
        assertEquals(NetFailure.UNREACHABLE, NetFailure.describe(Exception("Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect\"")))
        assertEquals(NetFailure.SECURE, NetFailure.describe(Exception("Error Domain=NSURLErrorDomain Code=-1202 \"untrusted\"")))
    }

    @Test fun jvmExceptions() {
        assertEquals(NetFailure.UNREACHABLE, NetFailure.describe(java.net.UnknownHostException("Unable to resolve host \"nhentai.net\"")))
        assertEquals(NetFailure.TIMEOUT, NetFailure.describe(java.net.SocketTimeoutException("connect timed out")))
        assertEquals(NetFailure.UNREACHABLE, NetFailure.describe(java.net.ConnectException("Failed to connect to nhentai.net/1.2.3.4:443")))
        assertEquals(NetFailure.SECURE, NetFailure.describe(javax.net.ssl.SSLHandshakeException("handshake failed")))
        assertEquals(NetFailure.OFFLINE, NetFailure.describe(java.io.IOException("x", java.net.NoRouteToHostException("Network is unreachable"))))
    }

    @Test fun otherErrorsAreLeftAlone() {
        assertNull(NetFailure.describeOrNull(IllegalStateException("Bad JSON")))
        assertEquals(NetFailure.OTHER, NetFailure.describe(IllegalStateException("Bad JSON")))
    }
}
