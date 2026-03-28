package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    bufferFactory: BufferFactory,
    bufferPool: BufferPool?,
    preferNative: Boolean,
): WebSocketClient = DefaultWebSocketClient(connectionOptions, parentScope, bufferFactory, externalPool = bufferPool)
