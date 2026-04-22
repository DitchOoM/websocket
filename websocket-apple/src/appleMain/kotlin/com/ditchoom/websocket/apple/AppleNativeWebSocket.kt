package com.ditchoom.websocket.apple

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.codecs.EmptyCodec
import kotlinx.coroutines.CoroutineScope

/**
 * Create an Apple-native WebSocket connection via `NSURLSessionWebSocketTask`.
 *
 * This is the Apple platform entry point for consumers who want the smallest
 * possible binary (no `com.ditchoom:socket` dependency). The system handles
 * TLS, proxy configuration, and permessage-deflate negotiation.
 *
 * For full RFC 6455 control (custom window bits, context takeover, zero-copy
 * framing), use `connectTcpWebSocket` from `com.ditchoom:websocket-tcp` instead.
 */
suspend fun <B> connectAppleNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<B>> {
    val controller =
        AppleWebSocketController(
            connectionOptions = connectionOptions,
            binaryCodec = binaryCodec,
            bufferFactory = connectionOptions.bufferFactory,
            parentScope = parentScope,
        )
    controller.connect()
    return controller
}

/** No-binary overload — binary frames surface as `Binary(Unit)`. */
suspend fun connectAppleNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<Unit>> = connectAppleNativeWebSocket(connectionOptions, EmptyCodec, parentScope)
