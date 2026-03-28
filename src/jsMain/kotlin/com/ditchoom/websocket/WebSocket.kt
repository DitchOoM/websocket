package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.shared
import com.ditchoom.socket.isNodeJs
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = false
internal actual val supportsDeflateContextTakeover: Boolean = false

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    bufferFactory: BufferFactory,
    bufferPool: BufferPool?,
    preferNative: Boolean,
): WebSocketClient =
    if (isNodeJs) {
        DefaultWebSocketClient(connectionOptions, parentScope, bufferFactory, externalPool = bufferPool)
    } else {
        val useSharedMemory = bufferFactory == BufferFactory.shared()
        BrowserWebSocketController(connectionOptions, bufferPool ?: BufferPool(), parentScope, useSharedMemory)
    }
