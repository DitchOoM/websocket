package com.ditchoom.websocket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.CoroutineScope

/**
 * WebSocket client implementation selection.
 */
enum class WebSocketImplementation {
    /** Original implementation with inline frame handling.
     * @deprecated Use MODULAR for better performance and RFC compliance.
     */
    @Deprecated("Use MODULAR implementation for better performance")
    DEFAULT,

    /** Modular implementation using FrameReader/FrameWriter/MessageAssembler.
     * This is the recommended implementation with:
     * - Better performance with zero-copy buffer operations
     * - Improved RFC 6455 compliance
     * - Proper cancellation support
     */
    MODULAR,
}

expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    pool: BufferPool,
    parentScope: CoroutineScope? = null,
    allocationZone: AllocationZone = AllocationZone.Direct,
    implementation: WebSocketImplementation = WebSocketImplementation.MODULAR,
): WebSocketClient

/**
 * Convenience overload that creates a BufferPool from AllocationZone.
 * For better performance, prefer passing a shared BufferPool.
 */
fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    zone: AllocationZone = AllocationZone.Direct,
    parentScope: CoroutineScope? = null,
    implementation: WebSocketImplementation = WebSocketImplementation.MODULAR,
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
        implementation,
    )
