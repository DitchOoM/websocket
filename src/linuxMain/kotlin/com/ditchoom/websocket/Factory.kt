package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

actual suspend fun connectNativeWebSocket(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope?,
    bufferFactory: BufferFactory,
    bufferPool: BufferPool?,
): Connection<WebSocketMessage> =
    throw UnsupportedOperationException(
        "Linux: Use connectWebSocket(transport, options) with a pre-connected ByteStream",
    )
