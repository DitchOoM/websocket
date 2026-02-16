@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.websocket.handshake

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create

/**
 * Apple implementation of Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2.
 *
 * Uses CommonCrypto for SHA-1.
 */
actual fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    val inputBytes = concatenated.encodeToByteArray()
    val hashBytes = ByteArray(CC_SHA1_DIGEST_LENGTH)

    inputBytes.usePinned { inputPinned ->
        hashBytes.usePinned { hashPinned ->
            CC_SHA1(
                inputPinned.addressOf(0),
                inputBytes.size.convert(),
                hashPinned.addressOf(0).reinterpret(),
            )
        }
    }

    val nsData = hashBytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = hashBytes.size.convert())
    }

    return nsData.base64EncodedStringWithOptions(0u)
}
