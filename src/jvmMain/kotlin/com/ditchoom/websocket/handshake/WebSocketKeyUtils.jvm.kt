package com.ditchoom.websocket.handshake

import java.security.MessageDigest
import java.util.Base64

/**
 * JVM/Android implementation of Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2.
 */
actual fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    val sha1 = MessageDigest.getInstance("SHA-1")
    val hash = sha1.digest(concatenated.toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(hash)
}
