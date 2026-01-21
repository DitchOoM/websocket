package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.CoroutineScope

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    pool: BufferPool,
    parentScope: CoroutineScope? = null,
    allocationZone: AllocationZone = AllocationZone.Direct,
): WebSocketClient

/**
 * Convenience overload that creates a BufferPool from AllocationZone.
 * For better performance, prefer passing a shared BufferPool.
 */
fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone = AllocationZone.Direct,
    parentScope: CoroutineScope? = null,
): WebSocketClient =
    allocate(
        connectionOptions,
        BufferPool(
            threadingMode = ThreadingMode.SingleThreaded,
            defaultBufferSize = DEFAULT_NETWORK_BUFFER_SIZE,
            allocationZone = zone,
        ),
        parentScope,
        zone,
    )
