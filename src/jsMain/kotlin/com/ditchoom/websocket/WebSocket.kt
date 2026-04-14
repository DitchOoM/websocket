package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.shared
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

/**
 * Detect whether we're running in Node.js (vs. browser).
 */
internal val isNodeJs: Boolean =
    js("typeof process !== 'undefined' && typeof process.versions !== 'undefined' && typeof process.versions.node !== 'undefined'") as Boolean

actual suspend fun <P> connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    payloadCodec: PayloadCodec<P>,
    parentScope: CoroutineScope?,
): Connection<WebSocketMessage<P>> =
    if (isNodeJs) {
        throw UnsupportedOperationException(
            "Node.js: Use connectWebSocket(transport, options, payloadCodec) with a pre-connected ByteStream",
        )
    } else {
        val bufferFactory = connectionOptions.bufferFactory
        val useSharedMemory = bufferFactory == BufferFactory.shared()
        val controller = BrowserWebSocketController(connectionOptions, payloadCodec, bufferFactory, parentScope, useSharedMemory)
        controller.connect()
        controller
    }
