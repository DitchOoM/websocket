package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

/**
 * Create a [WebSocketClient] for the given connection options.
 *
 * @param connectionOptions Connection configuration (host, port, TLS, compression, etc.)
 * @param parentScope Optional parent coroutine scope. If null, a new scope is created.
 * @param bufferFactory Buffer factory for frame I/O allocations.
 * @param bufferPool Optional buffer pool for memory reuse.
 * @param preferNative When true, uses the platform's native WebSocket implementation if available
 *   (Apple Network.framework, browser WebSocket API). Falls back to the RFC 6455 implementation
 *   on platforms without native support. Native implementations may not support all features
 *   (e.g., permessage-deflate compression control).
 */
expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
    preferNative: Boolean = false,
): WebSocketClient
