package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
): WebSocketClient
