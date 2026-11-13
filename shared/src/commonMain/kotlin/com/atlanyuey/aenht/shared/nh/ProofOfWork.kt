package com.atlanyuey.aenht.shared.nh

import okio.ByteString.Companion.encodeUtf8

/**
 * The API's proof of work for posting: a nonce such that
 * sha256(challenge + nonce) starts with `difficulty` zero bits.
 */
object ProofOfWork {
    fun solve(challenge: String, difficulty: Int): String {
        if (difficulty <= 0) return ""
        val fullBytes = difficulty / 8
        val remainingBits = difficulty % 8
        val mask = if (remainingBits == 0) 0 else (0xFF shl (8 - remainingBits)) and 0xFF
        var nonce = 0
        while (nonce < (1 shl 30)) {
            val hash = "$challenge$nonce".encodeUtf8().sha256()
            var ok = true
            for (i in 0 until fullBytes) {
                if (hash[i].toInt() != 0) {
                    ok = false
                    break
                }
            }
            if (ok && (mask == 0 || (hash[fullBytes].toInt() and mask) == 0)) return "$nonce"
            nonce++
        }
        return ""
    }
}
