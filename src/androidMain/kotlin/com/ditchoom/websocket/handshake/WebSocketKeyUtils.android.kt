package com.ditchoom.websocket.handshake

import android.annotation.SuppressLint
import android.os.Build
import android.util.Base64
import java.security.MessageDigest

/**
 * Android implementation of Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2.
 *
 * Handles both real Android runtime and JVM unit test environment.
 */
@SuppressLint("NewApi")
actual fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    val sha1 = MessageDigest.getInstance("SHA-1")
    val hash = sha1.digest(concatenated.toByteArray(Charsets.US_ASCII))
    // Build.VERSION.SDK_INT is 0 in JVM unit tests, use java.util.Base64 there
    return if (Build.VERSION.SDK_INT == 0) {
        java.util.Base64
            .getEncoder()
            .encodeToString(hash)
    } else {
        Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
