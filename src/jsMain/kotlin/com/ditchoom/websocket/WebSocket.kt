package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
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

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    bufferFactory: BufferFactory,
    bufferPool: BufferPool?,
): WebSocketClient =
    if (isNodeJs) {
        throw UnsupportedOperationException(
            "Node.js: Use allocate(transport, connectionOptions, ...) with a pre-connected WebSocketTransport",
        )
    } else {
        val useSharedMemory = bufferFactory == BufferFactory.shared()
        BrowserWebSocketController(connectionOptions, bufferPool ?: BufferPool(), parentScope, useSharedMemory)
    }
