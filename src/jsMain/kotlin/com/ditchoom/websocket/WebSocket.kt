package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = false

/**
 * Detect whether we're running in Node.js (vs. browser).
 */
internal val isNodeJs: Boolean =
    js("typeof process !== 'undefined' && typeof process.versions !== 'undefined' && typeof process.versions.node !== 'undefined'") as Boolean

actual suspend fun connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    bufferFactory: BufferFactory,
    bufferPool: BufferPool?,
): Connection<WebSocketMessage> =
    if (isNodeJs) {
        throw UnsupportedOperationException(
            "Node.js: Use connectWebSocket(transport, options) with a pre-connected ByteStream",
        )
    } else {
        val useSharedMemory = bufferFactory == BufferFactory.shared()
        val controller = BrowserWebSocketController(connectionOptions, bufferPool ?: BufferPool(), parentScope, useSharedMemory)
        controller.connect()
        controller
    }
