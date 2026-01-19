package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    pool: BufferPool,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone,
): WebSocketClient {
    return try {
        DefaultWebSocketClient(connectionOptions, pool, parentScope, allocationZone)
    } catch (e: UnsupportedOperationException) {
        BrowserWebSocketController(connectionOptions, pool, parentScope, allocationZone)
    }
}
