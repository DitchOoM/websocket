package com.ditchoom.websocket.handshake

/**
 * JavaScript implementation of Sec-WebSocket-Accept computation.
 *
 * Per RFC 6455 Section 4.2.2.
 *
 * Uses Node.js crypto module.
 */
actual fun computeAcceptKey(clientKey: String): String {
    val concatenated = clientKey + WEBSOCKET_GUID
    return js(
        """
        require('crypto')
            .createHash('sha1')
            .update(concatenated)
            .digest('base64')
        """,
    ) as String
}
