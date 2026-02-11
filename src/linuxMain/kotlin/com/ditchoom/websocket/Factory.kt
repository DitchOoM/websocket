package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

internal actual val supportsCustomDeflateWindowBits: Boolean = true
internal actual val supportsDeflateContextTakeover: Boolean = true

actual fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    pool: BufferPool,
    parentScope: CoroutineScope?,
    allocationZone: AllocationZone,
    implementation: WebSocketImplementation,
): WebSocketClient =
    when (implementation) {
        WebSocketImplementation.DEFAULT -> DefaultWebSocketClient(connectionOptions, pool, parentScope, allocationZone)
        WebSocketImplementation.MODULAR -> ModularWebSocketClient(connectionOptions, pool, parentScope, allocationZone)
    }
