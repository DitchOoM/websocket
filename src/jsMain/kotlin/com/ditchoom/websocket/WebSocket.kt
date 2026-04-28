package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.shared
import com.ditchoom.websocket.codecs.EmptyCodec
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

/**
 * Detect whether we're running in Node.js (vs. browser).
 */
internal val isNodeJs: Boolean =
    js(
        "typeof process !== 'undefined' && typeof process.versions !== 'undefined' && typeof process.versions.node !== 'undefined'",
    ) as Boolean

/**
 * Create a browser-native WebSocket connection via [BrowserWebSocketController].
 *
 * This is the browser JS entry point. The browser owns TLS, the HTTP upgrade,
 * frame encoding/masking, and permessage-deflate negotiation — the library
 * never touches those code paths on this target.
 *
 * On Node.js, use `connectTcpWebSocket` from `com.ditchoom:websocket-tcp` instead,
 * or call [connectWebSocket] with a pre-connected `ByteStream`.
 *
 * See `docs/docs/platforms/javascript.md` for the browser limitation matrix.
 */
suspend fun <B> connectBrowserWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    binaryCodec: Codec<B>,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<B>> {
    if (isNodeJs) {
        throw UnsupportedOperationException(
            "Node.js: Use connectTcpWebSocket from websocket-tcp, or connectWebSocket(transport, options, binaryCodec) with a pre-connected ByteStream",
        )
    }
    val bufferFactory = connectionOptions.bufferFactory
    val useSharedMemory = bufferFactory == BufferFactory.shared()
    val controller = BrowserWebSocketController(connectionOptions, binaryCodec, bufferFactory, parentScope, useSharedMemory)
    controller.connect()
    return controller
}

/** No-binary overload — see [connectBrowserWebSocket] for semantics. */
suspend fun connectBrowserWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
): Connection<WebSocketMessage<Unit>> = connectBrowserWebSocket(connectionOptions, EmptyCodec, parentScope)
