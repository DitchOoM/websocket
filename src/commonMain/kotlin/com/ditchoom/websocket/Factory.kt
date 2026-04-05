package com.ditchoom.websocket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.CoroutineScope

/**
 * Create a [DefaultWebSocketClient] for the given transport and connection options.
 *
 * The consumer must provide a pre-connected [WebSocketTransport]. The returned
 * client handles the HTTP upgrade handshake and WebSocket framing on top.
 *
 * @param transport A pre-connected byte transport (e.g. TCP socket).
 * @param connectionOptions Connection configuration (host, port, compression, etc.)
 * @param parentScope Optional parent coroutine scope for structured concurrency.
 * @param bufferFactory Buffer factory for frame I/O allocations.
 * @param bufferPool Optional buffer pool for memory reuse.
 */
fun WebSocketClient.Companion.allocate(
    transport: WebSocketTransport,
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
): WebSocketClient =
    DefaultWebSocketClient(
        transport = transport,
        connectionOptions = connectionOptions,
        parentScope = parentScope,
        bufferFactory = bufferFactory,
        externalPool = bufferPool,
    )

/**
 * Create a platform-specific [WebSocketClient] for the given connection options.
 *
 * On platforms with native WebSocket support (browser, Apple), this creates a
 * native client that handles transport internally. On other platforms (JVM, Linux),
 * this throws [UnsupportedOperationException] — use [allocate] with a [WebSocketTransport] instead.
 *
 * @param connectionOptions Connection configuration (host, port, TLS, compression, etc.)
 * @param parentScope Optional parent coroutine scope.
 * @param bufferFactory Buffer factory for frame I/O allocations.
 * @param bufferPool Optional buffer pool for memory reuse.
 */
expect fun WebSocketClient.Companion.allocate(
    connectionOptions: WebSocketConnectionOptions,
    parentScope: CoroutineScope? = null,
    bufferFactory: BufferFactory = BufferFactory.deterministic(),
    bufferPool: BufferPool? = null,
): WebSocketClient
