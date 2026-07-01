package com.ditchoom.websocket

actual suspend fun connectNativeWebSocket(
    url: String,
    tls: Boolean,
    verifyCertificates: Boolean,
    autoReplyPing: Boolean,
    subprotocols: List<String>?,
    timeoutSeconds: Int,
): NativeWebSocketConnection =
    throw UnsupportedOperationException(
        "NativeWebSocketConnection requires the com.ditchoom:socket dependency for Apple Network.framework support",
    )
